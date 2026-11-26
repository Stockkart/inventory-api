package com.inventory.plan.service.wallet;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.common.tx.MongoTx;
import com.inventory.plan.domain.model.ShopCredit;
import com.inventory.plan.domain.model.ShopCreditEntry;
import com.inventory.plan.domain.model.ShopCreditSource;
import com.inventory.plan.domain.repository.ShopCreditEntryRepository;
import com.inventory.plan.domain.repository.ShopCreditRepository;
import com.inventory.plan.mapper.WalletMapper;
import com.inventory.plan.rest.dto.response.WalletResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The referral wallet (§12, r4.3, §27.4). Each change reads the wallet, computes the new balances,
 * and writes them only if nobody changed the wallet meanwhile (compare-and-set on {@code version}).
 * The balances and the change's ledger row are written in one {@link MongoTx} transaction, and a
 * change whose reference id already has a ledger row for the shop is never applied twice. The
 * wallet also keeps its last {@value #RECENT_REFERENCES} reference ids, checked in the same write,
 * as a guard for runs with transactions switched off.
 *
 * <p>An order's credit moves in cycles: reserved at checkout, released when the order ends unpaid,
 * held again (reacquired) if it is paid late, and so on. The ledger rows of the order say how much
 * it holds right now, so a release only ever frees credit the order itself holds, and each cycle's
 * release has its own reference id.
 */
@Slf4j
@Service
public class WalletService {

  static final int MAX_ATTEMPTS = 10;
  static final int RECENT_REFERENCES = 20;
  private static final int RECENT_ENTRIES = 50;

  @Autowired
  private ShopCreditRepository creditRepository;

  @Autowired
  private ShopCreditEntryRepository entryRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private WalletMapper walletMapper;

  @Autowired
  private MongoTx mongoTx;

  Clock clock = Clock.systemUTC();

  public enum Outcome { APPLIED, ALREADY_APPLIED, REJECTED }

  public BigDecimal available(String shopId) {
    return balances(shopId).available();
  }

  /** The shop's balances; zero when it has no wallet yet. */
  public WalletBalances balances(String shopId) {
    return creditRepository.findBalancesById(shopId).map(WalletBalances::of).orElse(WalletBalances.ZERO);
  }

  public WalletResponse describe(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop not selected or user not authenticated");
    }
    return walletMapper.toResponse(balances(shopId),
        entryRepository.findByShopIdOrderByCreatedAtDesc(shopId, PageRequest.of(0, RECENT_ENTRIES)));
  }

  /**
   * The wallet as the shop sees it: only rows that moved its available balance. A paid order's
   * {@code ORDER_REDEMPTION} only spends credit its checkout already took from available (the
   * {@code ORDER_RESERVATION} row), so it would show as a +0 line; it stays in the ledger.
   */
  public WalletResponse describeForShop(String shopId) {
    WalletResponse wallet = describe(shopId);
    wallet.setEntries(wallet.getEntries().stream()
        .filter(entry -> entry.getAvailableDelta() != null && entry.getAvailableDelta().signum() != 0)
        .toList());
    return wallet;
  }

  /** Holds credit for an order at checkout. False when the balance no longer covers it. */
  public boolean reserve(String shopId, String orderId, BigDecimal amount) {
    return apply(shopId, "order-reserve:" + orderId, ShopCreditSource.ORDER_RESERVATION, orderId, amount,
        null, null, b -> b.reserve(scale(amount))) != Outcome.REJECTED;
  }

  /**
   * Holds credit again for an order paid after it had ended. True when the order holds it (also
   * when it already did); false when the balance no longer covers it.
   */
  public boolean reacquire(String shopId, String orderId, BigDecimal amount) {
    return run(shopId, () -> {
      OrderHold hold = hold(shopId, orderId);
      if (hold.held() || hold.consumed()) {
        return true;
      }
      return apply(shopId, "order-reacquire:" + orderId + ":" + hold.releases(), ShopCreditSource.ORDER_RESERVATION,
          orderId, amount, "Reacquired for a late payment", null, b -> b.reserve(scale(amount))) != Outcome.REJECTED;
    });
  }

  /**
   * Gives back what the order holds. Does nothing when the order holds nothing (never reserved,
   * already released, or spent), so it can never free another order's credit.
   */
  public void release(String shopId, String orderId, BigDecimal amount) {
    run(shopId, () -> {
      OrderHold hold = hold(shopId, orderId);
      if (!hold.held()) {
        log.info("Order {} of shop {} holds no wallet credit; nothing to release", orderId, shopId);
        return null;
      }
      if (apply(shopId, "order-release:" + orderId + ":" + hold.holds(), ShopCreditSource.RESERVATION_RELEASE,
          orderId, amount, null, null, b -> b.release(scale(amount))) == Outcome.REJECTED) {
        log.error("Wallet of shop {} does not hold {} for order {}; nothing released", shopId, amount, orderId);
      }
      return null;
    });
  }

  /** Spends an order's reservation once it is paid. */
  public void consume(String shopId, String orderId, BigDecimal amount) {
    run(shopId, () -> {
      OrderHold hold = hold(shopId, orderId);
      if (hold.consumed()) {
        return null;
      }
      if (!hold.held() || apply(shopId, "order-consume:" + orderId, ShopCreditSource.ORDER_REDEMPTION, orderId,
          amount, null, null, b -> b.consume(scale(amount))) == Outcome.REJECTED) {
        throw new IllegalStateException(
            "Wallet of shop " + shopId + " does not hold " + amount + " for order " + orderId);
      }
      return null;
    });
  }

  /** Adds money, paying off any outstanding clawback first. Idempotent per (source, sourceId). */
  public Outcome credit(String shopId, BigDecimal amount, ShopCreditSource source, String sourceId,
      String note, String actorUserId) {
    return apply(shopId, source.name().toLowerCase(Locale.ROOT) + ":" + sourceId, source, sourceId, amount, note, actorUserId,
        b -> Optional.of(b.credit(scale(amount))));
  }

  /** Takes back a credited referral reward. Idempotent per reward. */
  public Outcome clawback(String shopId, BigDecimal amount, String rewardId, String note, String actorUserId) {
    return apply(shopId, "clawback:" + rewardId, ShopCreditSource.CLAWBACK, rewardId, amount, note, actorUserId,
        b -> Optional.of(b.clawback(scale(amount))));
  }

  /**
   * An operator's manual adjustment: a positive amount is credited (paying off outstanding first), a
   * negative one is taken from available only and rejected if not enough is available. Idempotent per
   * adjustment id within the shop.
   */
  public Outcome adjust(String shopId, BigDecimal signedAmount, String adjustmentId, String reason, String actorUserId) {
    BigDecimal amount = scale(signedAmount.abs());
    return apply(shopId, "manual_adjustment:" + adjustmentId, ShopCreditSource.MANUAL_ADJUSTMENT, adjustmentId,
        amount, reason, actorUserId, adjustment(signedAmount));
  }

  /** The balances an adjustment would leave, or empty when it would be rejected. Writes nothing. */
  public Optional<WalletBalances> previewAdjustment(String shopId, BigDecimal signedAmount) {
    return adjustment(signedAmount).apply(balances(shopId));
  }

  private static Function<WalletBalances, Optional<WalletBalances>> adjustment(BigDecimal signedAmount) {
    BigDecimal amount = scale(signedAmount.abs());
    return signedAmount.signum() > 0
        ? b -> Optional.of(b.credit(amount))
        : b -> b.debit(amount);
  }

  /** True once the order's reservation has been spent (fulfilment ran its wallet step). */
  public boolean consumed(String orderId) {
    return entryRepository.existsByReferenceId("order-consume:" + orderId);
  }

  /** What an order holds now, from its ledger rows: holds (reserve, reacquire) minus releases and spends. */
  record OrderHold(long holds, long releases, long consumes) {
    boolean held() {
      return holds > releases + consumes;
    }

    boolean consumed() {
      return consumes > 0;
    }
  }

  OrderHold hold(String shopId, String orderId) {
    return new OrderHold(
        entryRepository.countByShopIdAndSourceIdAndSource(shopId, orderId, ShopCreditSource.ORDER_RESERVATION),
        entryRepository.countByShopIdAndSourceIdAndSource(shopId, orderId, ShopCreditSource.RESERVATION_RELEASE),
        entryRepository.countByShopIdAndSourceIdAndSource(shopId, orderId, ShopCreditSource.ORDER_REDEMPTION));
  }

  Outcome apply(String shopId, String referenceId, ShopCreditSource source, String sourceId, BigDecimal amount,
      String note, String actorUserId, Function<WalletBalances, Optional<WalletBalances>> change) {
    if (scale(amount).signum() <= 0) {
      throw new ValidationException("Wallet amount must be positive");
    }
    if (entryRepository.existsByShopIdAndReferenceId(shopId, referenceId)) {
      return Outcome.ALREADY_APPLIED;
    }
    if (creditRepository.findBalancesById(shopId).isEmpty()) {
      openWallet(shopId); // outside the transaction: a duplicate-key retry must not abort it
    }
    return run(shopId, () -> applyOnce(shopId, referenceId, source, sourceId, amount, note, actorUserId, change));
  }

  private Outcome applyOnce(String shopId, String referenceId, ShopCreditSource source, String sourceId,
      BigDecimal amount, String note, String actorUserId, Function<WalletBalances, Optional<WalletBalances>> change) {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      if (entryRepository.existsByShopIdAndReferenceId(shopId, referenceId)) {
        return Outcome.ALREADY_APPLIED;
      }
      ShopCredit current = creditRepository.findBalancesById(shopId)
          .orElseThrow(() -> new IllegalStateException("Wallet of shop " + shopId + " is missing"));
      WalletBalances before = WalletBalances.of(current);
      Optional<WalletBalances> after = change.apply(before);
      if (after.isEmpty()) {
        return Outcome.REJECTED;
      }
      Instant now = clock.instant();
      if (compareAndSet(current, after.get(), referenceId, now)) {
        entryRepository.insert(walletMapper.toEntry(shopId, referenceId, source, sourceId, scale(amount), before,
            after.get(), note, actorUserId, now));
        return Outcome.APPLIED;
      }
      if (recentlyApplied(shopId, referenceId)) {
        log.error("Wallet change {} for shop {} was applied but has no ledger entry", referenceId, shopId);
        return Outcome.ALREADY_APPLIED;
      }
    }
    throw new BaseException(ErrorCode.SERVICE_BUSY, "The wallet is busy, please try again in a moment");
  }

  private <T> T run(String shopId, java.util.function.Supplier<T> work) {
    return mongoTx.run(MongoTx.WALLET, shopId, work);
  }

  private boolean compareAndSet(ShopCredit current, WalletBalances next, String referenceId, Instant now) {
    Query unchanged = new Query(Criteria.where("_id").is(current.getShopId())
        .and("version").is(current.getVersion())
        .and("recentReferences").ne(referenceId));
    Update update = new Update()
        .set("availableBalance", next.available())
        .set("reservedBalance", next.reserved())
        .set("outstandingClawback", next.outstanding())
        .set("updatedAt", now)
        .inc("version", 1);
    update.push("recentReferences").slice(-RECENT_REFERENCES).each(referenceId);
    return mongoTemplate.updateFirst(unchanged, update, ShopCredit.class).getModifiedCount() == 1;
  }

  private boolean recentlyApplied(String shopId, String referenceId) {
    return mongoTemplate.exists(
        new Query(Criteria.where("_id").is(shopId).and("recentReferences").is(referenceId)), ShopCredit.class);
  }

  private void openWallet(String shopId) {
    Instant now = clock.instant();
    ShopCredit empty = ShopCredit.builder()
        .shopId(shopId)
        .availableBalance(WalletBalances.ZERO.available())
        .reservedBalance(WalletBalances.ZERO.reserved())
        .outstandingClawback(WalletBalances.ZERO.outstanding())
        .createdAt(now)
        .updatedAt(now)
        .build();
    try {
      creditRepository.insert(empty);
    } catch (DuplicateKeyException e) {
      log.debug("Wallet of shop {} was opened concurrently", shopId);
    }
  }

  private static BigDecimal scale(BigDecimal amount) {
    return WalletBalances.scale(amount);
  }
}
