package com.inventory.plan.service.referral;

import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.ReferralReward;
import com.inventory.plan.domain.model.ReferralRewardStatus;
import com.inventory.plan.domain.model.ShopCreditSource;
import com.inventory.plan.domain.repository.ReferralRewardRepository;
import com.inventory.plan.mapper.ReferralRewardMapper;
import com.inventory.plan.rest.dto.response.ReferralRewardsResponse;
import com.inventory.plan.service.ShopProvider;
import com.inventory.plan.service.wallet.WalletService;
import com.inventory.plan.utils.constants.PricingConstants;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Referral cashback (§11, §24). A reward is recorded when the referee's first plan order is fulfilled,
 * held for the hold period, then credited to the referrer's wallet by the hold-release job.
 */
@Slf4j
@Service
public class ReferralRewardService {

  /** Hard ceiling whatever the configuration says (§24). */
  static final BigDecimal MAX_REWARD_PERCENT = BigDecimal.TEN;
  static final String VOID_CAP_REACHED = "REFERRER_CAP_REACHED";
  /** A CREDITING claim older than this is taken to be from a crashed run and resumed. */
  static final Duration CREDITING_LEASE = Duration.ofMinutes(5);
  private static final Set<ReferralRewardStatus> COUNTS_TOWARDS_CAP = EnumSet.of(ReferralRewardStatus.PENDING,
      ReferralRewardStatus.APPROVED, ReferralRewardStatus.CREDITING, ReferralRewardStatus.CREDITED,
      ReferralRewardStatus.CLAWED_BACK);
  private static final Set<ReferralRewardStatus> RELEASABLE =
      EnumSet.of(ReferralRewardStatus.PENDING, ReferralRewardStatus.APPROVED);
  private static final int LIST_LIMIT = 100;

  @Autowired
  private ReferralRewardRepository rewardRepository;

  @Autowired
  private ReferralAttributionService attributionService;

  @Autowired
  private WalletService walletService;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private ShopProvider shopProvider;

  @Autowired
  private ReferralRewardMapper rewardMapper;

  @Value("${referral.reward.percent:10}")
  BigDecimal configuredPercent = BigDecimal.TEN;

  @Value("${referral.reward.hold-days:15}")
  long holdDays = 15;

  @Value("${referral.reward.max-per-referrer:20}")
  long maxPerReferrer = 20;

  @Value("${referral.reward.cap-period-days:365}")
  long capPeriodDays = 365;

  Clock clock = Clock.systemUTC();

  /**
   * The reward for a fulfilled order, if the referee has a resolved referrer and paid something for
   * the plan. At most one per referee: a later order never earns a second reward.
   */
  public Optional<ReferralReward> recordForOrder(PlanPaymentOrder order) {
    Optional<String> referrer = attributionService.resolvedReferrer(order.getShopId());
    if (referrer.isEmpty() || order.getItems() == null) {
      return Optional.empty();
    }
    BigDecimal base = rewardBase(order.getItems());
    if (base.signum() <= 0) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    BigDecimal percent = effectivePercent();
    ReferralReward reward = ReferralReward.builder()
        .referrerShopId(referrer.get())
        .refereeShopId(order.getShopId())
        .orderId(order.getId())
        .planCode(order.getPlanCode())
        .basePlanAmount(base)
        .rewardPercent(percent)
        .rewardAmount(rewardAmount(base, percent))
        .status(ReferralRewardStatus.PENDING)
        .holdUntil(now.plus(Duration.ofDays(holdDays)))
        .createdAt(now)
        .updatedAt(now)
        .build();
    if (capReached(referrer.get(), now)) {
      reward.setStatus(ReferralRewardStatus.VOID);
      reward.setVoidReason(VOID_CAP_REACHED);
      reward.setVoidedAt(now);
    }
    try {
      return Optional.of(rewardRepository.insert(reward));
    } catch (DuplicateKeyException e) {
      return rewardRepository.findByOrderId(order.getId());
    }
  }

  /** The plan line as paid: after its voucher discount and after wallet credit (§24, decision 3). */
  static BigDecimal rewardBase(List<OrderLine> items) {
    return items.stream()
        .filter(line -> PricingConstants.ITEM_TYPE_PLAN.equals(line.getType()))
        .map(line -> nz(line.getLineTotal()).subtract(nz(line.getWalletCredit())))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .max(BigDecimal.ZERO);
  }

  static BigDecimal rewardAmount(BigDecimal base, BigDecimal percent) {
    return base.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
  }

  BigDecimal effectivePercent() {
    BigDecimal configured = configuredPercent == null ? BigDecimal.ZERO : configuredPercent;
    return configured.max(BigDecimal.ZERO).min(MAX_REWARD_PERCENT);
  }

  private boolean capReached(String referrerShopId, Instant now) {
    long recent = rewardRepository.countByReferrerShopIdAndStatusInAndCreatedAtAfter(
        referrerShopId, COUNTS_TOWARDS_CAP, now.minus(Duration.ofDays(capPeriodDays)));
    return recent >= maxPerReferrer;
  }

  /**
   * Credits rewards whose hold has ended. Each is claimed (→ CREDITING) before the wallet credit, so a
   * refund arriving meanwhile sees the claim instead of voiding money already on its way.
   */
  public int releaseDue(int batch) {
    Instant now = clock.instant();
    int credited = 0;
    for (ReferralReward due : rewardRepository.findByStatusInAndHoldUntilLessThanEqual(
        RELEASABLE, now, PageRequest.of(0, batch))) {
      Optional<ReferralReward> claimed = claim(due.getId(), now);
      if (claimed.isPresent() && finishCredit(claimed.get())) {
        credited++;
      }
    }
    for (ReferralReward stuck : rewardRepository.findByStatusAndUpdatedAtBefore(
        ReferralRewardStatus.CREDITING, now.minus(CREDITING_LEASE), PageRequest.of(0, batch))) {
      credited += finishCredit(stuck) ? 1 : 0;
    }
    return credited;
  }

  private Optional<ReferralReward> claim(String rewardId, Instant now) {
    Query releasable = new Query(Criteria.where("_id").is(rewardId)
        .and("status").in(RELEASABLE)
        .and("holdUntil").lte(now));
    Update update = new Update().set("status", ReferralRewardStatus.CREDITING).set("updatedAt", now);
    return Optional.ofNullable(mongoTemplate.findAndModify(releasable, update,
        FindAndModifyOptions.options().returnNew(true), ReferralReward.class));
  }

  /** Wallet credit (idempotent per reward), then CREDITING → CREDITED. */
  boolean finishCredit(ReferralReward reward) {
    try {
      walletService.credit(reward.getReferrerShopId(), reward.getRewardAmount(), ShopCreditSource.REFERRAL_REWARD,
          reward.getId(), "Referral reward for order " + reward.getOrderId(), null);
    } catch (RuntimeException e) {
      log.warn("Crediting referral reward {} failed; will retry: {}", reward.getId(), e.getMessage());
      return false;
    }
    Instant now = clock.instant();
    Query crediting = new Query(Criteria.where("_id").is(reward.getId()).and("status").is(ReferralRewardStatus.CREDITING));
    Update update = new Update().set("status", ReferralRewardStatus.CREDITED).set("creditedAt", now).set("updatedAt", now);
    return mongoTemplate.updateFirst(crediting, update, ReferralReward.class).getModifiedCount() > 0;
  }

  public ReferralRewardsResponse listForReferrer(String shopId) {
    List<ReferralReward> rewards =
        rewardRepository.findByReferrerShopIdOrderByCreatedAtDesc(shopId, PageRequest.of(0, LIST_LIMIT));
    Map<String, String> refereeNames = rewards.stream()
        .map(ReferralReward::getRefereeShopId)
        .distinct()
        .collect(Collectors.toMap(Function.identity(),
            id -> shopProvider.getReferralShop(id).map(ShopProvider.ReferralShop::name).orElse("")));
    return rewardMapper.toRewardsResponse(rewards, refereeNames);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
