package com.inventory.plan.service.wallet;

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

/**
 * The referral wallet (§12, r4.3, §27.4). Each change reads the wallet, computes the new balances,
 * and writes them only if nobody changed the wallet meanwhile (compare-and-set on {@code version}).
 * The change's reference id is recorded in the same write, so a retried change is never applied twice.
 */
@Slf4j
@Service
public class WalletService {

  static final int MAX_ATTEMPTS = 10;
  static final int RECENT_REFERENCES = 500;
  private static final int RECENT_ENTRIES = 50;

  @Autowired
  private ShopCreditRepository creditRepository;

  @Autowired
  private ShopCreditEntryRepository entryRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private WalletMapper walletMapper;

  Clock clock = Clock.systemUTC();

  public enum Outcome { APPLIED, ALREADY_APPLIED, REJECTED }

  public BigDecimal available(String shopId) {
    return creditRepository.findById(shopId).map(WalletBalances::of).orElse(WalletBalances.ZERO).available();
  }

  public WalletResponse describe(String shopId) {
    WalletBalances balances = creditRepository.findById(shopId).map(WalletBalances::of).orElse(WalletBalances.ZERO);
    return walletMapper.toResponse(balances,
        entryRepository.findByShopIdOrderByCreatedAtDesc(shopId, PageRequest.of(0, RECENT_ENTRIES)));
  }

  /** Holds credit for an order at checkout. False when the balance no longer covers it. */
  public boolean reserve(String shopId, String orderId, BigDecimal amount) {
    return apply(shopId, "order-reserve:" + orderId, ShopCreditSource.ORDER_RESERVATION, orderId, amount,
        null, null, b -> b.reserve(scale(amount))) != Outcome.REJECTED;
  }

  /** Holds credit again for an order paid after it had ended. False when it is gone. */
  public boolean reacquire(String shopId, String orderId, BigDecimal amount) {
    return apply(shopId, "order-reacquire:" + orderId, ShopCreditSource.ORDER_RESERVATION, orderId, amount,
        "Reacquired for a late payment", null, b -> b.reserve(scale(amount))) != Outcome.REJECTED;
  }

  public void release(String shopId, String orderId, BigDecimal amount) {
    if (apply(shopId, "order-release:" + orderId, ShopCreditSource.RESERVATION_RELEASE, orderId, amount,
        null, null, b -> b.release(scale(amount))) == Outcome.REJECTED) {
      log.error("Wallet of shop {} does not hold {} for order {}; nothing released", shopId, amount, orderId);
    }
  }

  /** Spends an order's reservation once it is paid. */
  public void consume(String shopId, String orderId, BigDecimal amount) {
    if (apply(shopId, "order-consume:" + orderId, ShopCreditSource.ORDER_REDEMPTION, orderId, amount,
        null, null, b -> b.consume(scale(amount))) == Outcome.REJECTED) {
      throw new IllegalStateException("Wallet of shop " + shopId + " does not hold " + amount + " for order " + orderId);
    }
  }

  /** Adds money, paying off any outstanding clawback first. Idempotent per (source, sourceId). */
  public Outcome credit(String shopId, BigDecimal amount, ShopCreditSource source, String sourceId,
      String note, String actorUserId) {
    return apply(shopId, source.name().toLowerCase(Locale.ROOT) + ":" + sourceId, source, sourceId, amount, note, actorUserId,
        b -> Optional.of(b.credit(scale(amount))));
  }

  Outcome apply(String shopId, String referenceId, ShopCreditSource source, String sourceId, BigDecimal amount,
      String note, String actorUserId, Function<WalletBalances, Optional<WalletBalances>> change) {
    if (scale(amount).signum() <= 0) {
      throw new IllegalArgumentException("Wallet amount must be positive");
    }
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      ShopCredit current = creditRepository.findById(shopId).orElseGet(() -> openWallet(shopId));
      if (current.getRecentReferences() != null && current.getRecentReferences().contains(referenceId)) {
        if (!entryRepository.existsByReferenceId(referenceId)) {
          log.error("Wallet change {} for shop {} was applied but has no ledger entry", referenceId, shopId);
        }
        return Outcome.ALREADY_APPLIED;
      }
      WalletBalances before = WalletBalances.of(current);
      Optional<WalletBalances> after = change.apply(before);
      if (after.isEmpty()) {
        return Outcome.REJECTED;
      }
      Instant now = clock.instant();
      if (compareAndSet(current, after.get(), referenceId, now)) {
        writeEntry(shopId, referenceId, source, sourceId, scale(amount), before, after.get(), note, actorUserId, now);
        return Outcome.APPLIED;
      }
    }
    throw new IllegalStateException("Wallet of shop " + shopId + " is too busy; retry");
  }

  private boolean compareAndSet(ShopCredit current, WalletBalances next, String referenceId, Instant now) {
    Query unchanged = new Query(Criteria.where("_id").is(current.getShopId()).and("version").is(current.getVersion()));
    Update update = new Update()
        .set("availableBalance", next.available())
        .set("reservedBalance", next.reserved())
        .set("outstandingClawback", next.outstanding())
        .set("updatedAt", now)
        .inc("version", 1);
    update.push("recentReferences").slice(-RECENT_REFERENCES).each(referenceId);
    return mongoTemplate.updateFirst(unchanged, update, ShopCredit.class).getModifiedCount() == 1;
  }

  private ShopCredit openWallet(String shopId) {
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
      return creditRepository.insert(empty);
    } catch (DuplicateKeyException e) {
      return creditRepository.findById(shopId).orElseThrow(() -> e);
    }
  }

  private void writeEntry(String shopId, String referenceId, ShopCreditSource source, String sourceId,
      BigDecimal amount, WalletBalances before, WalletBalances after, String note, String actorUserId, Instant now) {
    try {
      entryRepository.insert(walletMapper.toEntry(shopId, referenceId, source, sourceId, amount, before, after,
          note, actorUserId, now));
    } catch (DuplicateKeyException e) {
      log.info("Ledger entry {} already written", referenceId);
    }
  }

  private static BigDecimal scale(BigDecimal amount) {
    return WalletBalances.scale(amount);
  }
}
