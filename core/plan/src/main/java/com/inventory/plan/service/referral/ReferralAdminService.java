package com.inventory.plan.service.referral;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.ReferralAttribution;
import com.inventory.plan.domain.model.ReferralAttributionStatus;
import com.inventory.plan.domain.model.ReferralReward;
import com.inventory.plan.domain.model.ReferralRewardStatus;
import com.inventory.plan.domain.repository.PlanPaymentOrderRepository;
import com.inventory.plan.domain.repository.ReferralAttributionRepository;
import com.inventory.plan.domain.repository.ReferralRewardRepository;
import com.inventory.plan.mapper.ReferralAdminMapper;
import com.inventory.plan.rest.dto.request.WalletAdjustmentRequest;
import com.inventory.plan.rest.dto.response.AdminReferralAttributionResponse;
import com.inventory.plan.rest.dto.response.AdminReferralRewardResponse;
import com.inventory.plan.rest.dto.response.WalletResponse;
import com.inventory.plan.service.ShopProvider;
import com.inventory.plan.service.wallet.WalletService;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Referral operations for platform admins (5d): the attribution review queue, reward approval, void
 * and clawback, and manual wallet adjustments. Every action needs a reason and is audited (§13).
 */
@Slf4j
@Service
public class ReferralAdminService {

  static final int LIST_LIMIT = 200;
  private static final String ATTRIBUTION = "REFERRAL_ATTRIBUTION";
  private static final String REWARD = "REFERRAL_REWARD";
  private static final String WALLET = "SHOP_WALLET";

  @Autowired
  private ReferralAttributionRepository attributionRepository;

  @Autowired
  private ReferralRewardRepository rewardRepository;

  @Autowired
  private PlanPaymentOrderRepository orderRepository;

  @Autowired
  private ReferralRewardService rewardService;

  @Autowired
  private WalletService walletService;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private ShopProvider shopProvider;

  @Autowired
  private ReferralAdminMapper mapper;

  @Autowired
  private AuditService auditService;

  Clock clock = Clock.systemUTC();

  public List<AdminReferralAttributionResponse> listAttributions(ReferralAttributionStatus status) {
    ReferralAttributionStatus wanted = status == null ? ReferralAttributionStatus.PENDING_REVIEW : status;
    Map<String, String> names = new HashMap<>();
    return attributionRepository.findByStatusOrderByCreatedAtAsc(wanted, PageRequest.of(0, LIST_LIMIT)).stream()
        .map(a -> mapper.toAttributionResponse(a, name(a.getRefereeShopId(), names), name(a.getReferrerShopId(), names)))
        .toList();
  }

  /**
   * PENDING_REVIEW → RESOLVED. If the referee already has a fulfilled plan order, its reward is
   * recorded now, as it would have been had the attribution resolved at sign-up.
   */
  public AdminReferralAttributionResponse approveAttribution(String id, String referrerShopId, String reason,
      String actorUserId) {
    requireReason(reason);
    ReferralAttribution attribution = loadAttribution(id);
    String referrer = StringUtils.hasText(referrerShopId) ? referrerShopId.trim() : attribution.getReferrerShopId();
    if (!StringUtils.hasText(referrer)) {
      throw new ValidationException("Choose the referring shop: this referral has no code to go by");
    }
    if (referrer.equals(attribution.getRefereeShopId())) {
      throw new ValidationException("A shop cannot refer itself");
    }
    if (shopProvider.getReferralShop(referrer).isEmpty()) {
      throw new ResourceNotFoundException("Shop", "id", referrer);
    }
    Instant now = clock.instant();
    ReferralAttribution resolved = transitionAttribution(id, new Update()
        .set("status", ReferralAttributionStatus.RESOLVED)
        .set("referrerShopId", referrer)
        .set("resolvedAt", now)
        .set("resolvedByUserId", actorUserId));
    audit("REFERRAL_APPROVED", ATTRIBUTION, id, attributionSnapshot(attribution), attributionSnapshot(resolved),
        reason, actorUserId);
    orderRepository.findFirstByShopIdAndStatusOrderByFulfilledAtAsc(resolved.getRefereeShopId(),
            PlanPaymentConstants.STATUS_FULFILLED)
        .ifPresent(rewardService::recordForOrder);
    return toResponse(resolved);
  }

  /** PENDING_REVIEW → REJECTED. */
  public AdminReferralAttributionResponse rejectAttribution(String id, String reason, String actorUserId) {
    requireReason(reason);
    ReferralAttribution before = loadAttribution(id);
    ReferralAttribution rejected = transitionAttribution(id, new Update()
        .set("status", ReferralAttributionStatus.REJECTED)
        .set("rejectionReason", reason.trim())
        .set("resolvedAt", clock.instant())
        .set("resolvedByUserId", actorUserId));
    audit("REFERRAL_REJECTED", ATTRIBUTION, id, attributionSnapshot(before), attributionSnapshot(rejected),
        reason, actorUserId);
    return toResponse(rejected);
  }

  public List<AdminReferralRewardResponse> listRewards(ReferralRewardStatus status) {
    PageRequest page = PageRequest.of(0, LIST_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt"));
    List<ReferralReward> rewards = status == null
        ? rewardRepository.findAll(page).getContent()
        : rewardRepository.findByStatusOrderByCreatedAtDesc(status, page);
    Map<String, String> names = new HashMap<>();
    return rewards.stream()
        .map(r -> mapper.toRewardResponse(r, name(r.getRefereeShopId(), names), name(r.getReferrerShopId(), names)))
        .toList();
  }

  /** PENDING → APPROVED, cleared for crediting on the next hold-release run. */
  public AdminReferralRewardResponse approveReward(String id, String reason, String actorUserId) {
    requireReason(reason);
    ReferralReward before = loadReward(id);
    Instant now = clock.instant();
    Query pending = new Query(Criteria.where("_id").is(id).and("status").is(ReferralRewardStatus.PENDING));
    ReferralReward approved = Optional.ofNullable(mongoTemplate.findAndModify(pending, new Update()
            .set("status", ReferralRewardStatus.APPROVED)
            .set("approvedAt", now)
            .set("holdUntil", now)
            .set("updatedAt", now), FindAndModifyOptions.options().returnNew(true), ReferralReward.class))
        .orElseThrow(() -> wrongState("reward", before.getStatus(), "PENDING"));
    audit("REWARD_APPROVED", REWARD, id, rewardSnapshot(before), rewardSnapshot(approved), reason, actorUserId);
    return toResponse(approved);
  }

  /** PENDING or APPROVED → VOID. */
  public AdminReferralRewardResponse voidReward(String id, String reason, String actorUserId) {
    requireReason(reason);
    ReferralReward before = loadReward(id);
    ReferralReward voided = rewardService.voidReward(id,
            EnumSet.of(ReferralRewardStatus.PENDING, ReferralRewardStatus.APPROVED), "ADMIN: " + reason.trim())
        .orElseThrow(() -> wrongState("reward", before.getStatus(), "PENDING or APPROVED"));
    audit("REWARD_VOIDED", REWARD, id, rewardSnapshot(before), rewardSnapshot(voided), reason, actorUserId);
    return toResponse(voided);
  }

  /** CREDITED → CLAWED_BACK, taking the amount back from the referrer's wallet. */
  public AdminReferralRewardResponse clawBackReward(String id, String reason, String actorUserId) {
    requireReason(reason);
    ReferralReward before = loadReward(id);
    if (before.getStatus() != ReferralRewardStatus.CREDITED) {
      throw wrongState("reward", before.getStatus(), "CREDITED");
    }
    ReferralReward clawedBack = rewardService.reverseForOrder(before.getOrderId(), "ADMIN: " + reason.trim(), actorUserId)
        .orElseThrow(() -> new ResourceNotFoundException("Referral reward", "id", id));
    audit("REWARD_CLAWED_BACK", REWARD, id, rewardSnapshot(before), rewardSnapshot(clawedBack), reason, actorUserId);
    return toResponse(clawedBack);
  }

  public WalletResponse wallet(String shopId) {
    return walletService.describe(shopId);
  }

  public WalletResponse adjustWallet(String shopId, WalletAdjustmentRequest request, String actorUserId) {
    requireReason(request.getReason());
    BigDecimal amount = request.getAmount();
    if (amount == null || amount.signum() == 0) {
      throw new ValidationException("Adjustment amount must be non-zero");
    }
    if (shopProvider.getShop(shopId).isEmpty()) {
      throw new ResourceNotFoundException("Shop", "id", shopId);
    }
    String adjustmentId = StringUtils.hasText(request.getAdjustmentId())
        ? request.getAdjustmentId().trim() : UUID.randomUUID().toString();
    BigDecimal availableBefore = walletService.available(shopId);
    WalletService.Outcome outcome = walletService.adjust(shopId, amount, adjustmentId, request.getReason().trim(),
        actorUserId);
    if (outcome == WalletService.Outcome.REJECTED) {
      throw new ValidationException("Only " + availableBefore + " is available to take from this wallet");
    }
    if (outcome == WalletService.Outcome.APPLIED) {
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("adjustmentId", adjustmentId);
      after.put("amount", amount);
      after.put("availableBefore", availableBefore);
      after.put("availableAfter", walletService.available(shopId));
      audit("WALLET_ADJUSTED", WALLET, shopId, null, after, request.getReason(), actorUserId);
    }
    return walletService.describe(shopId);
  }

  private ReferralAttribution transitionAttribution(String id, Update update) {
    Query pending = new Query(Criteria.where("_id").is(id).and("status").is(ReferralAttributionStatus.PENDING_REVIEW));
    return Optional.ofNullable(mongoTemplate.findAndModify(pending, update,
            FindAndModifyOptions.options().returnNew(true), ReferralAttribution.class))
        .orElseThrow(() -> wrongState("referral", loadAttribution(id).getStatus(), "PENDING_REVIEW"));
  }

  private ReferralAttribution loadAttribution(String id) {
    return attributionRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Referral attribution", "id", id));
  }

  private ReferralReward loadReward(String id) {
    return rewardRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Referral reward", "id", id));
  }

  private AdminReferralAttributionResponse toResponse(ReferralAttribution attribution) {
    Map<String, String> names = new HashMap<>();
    return mapper.toAttributionResponse(attribution, name(attribution.getRefereeShopId(), names),
        name(attribution.getReferrerShopId(), names));
  }

  private AdminReferralRewardResponse toResponse(ReferralReward reward) {
    Map<String, String> names = new HashMap<>();
    return mapper.toRewardResponse(reward, name(reward.getRefereeShopId(), names), name(reward.getReferrerShopId(), names));
  }

  private String name(String shopId, Map<String, String> cache) {
    if (shopId == null) {
      return null;
    }
    return cache.computeIfAbsent(shopId,
        id -> shopProvider.getReferralShop(id).map(ShopProvider.ReferralShop::name).orElse(null));
  }

  private static void requireReason(String reason) {
    if (!StringUtils.hasText(reason)) {
      throw new ValidationException("A reason is required");
    }
  }

  private static ValidationException wrongState(String what, Object status, String expected) {
    return new ValidationException("This " + what + " is " + status + "; only " + expected + " can be changed this way");
  }

  private static Map<String, Object> attributionSnapshot(ReferralAttribution a) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("status", a.getStatus());
    map.put("referrerShopId", a.getReferrerShopId());
    map.put("refereeShopId", a.getRefereeShopId());
    map.put("reviewReason", a.getReviewReason());
    return map;
  }

  private static Map<String, Object> rewardSnapshot(ReferralReward r) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("status", r.getStatus());
    map.put("rewardAmount", r.getRewardAmount());
    map.put("referrerShopId", r.getReferrerShopId());
    map.put("orderId", r.getOrderId());
    return map;
  }

  private void audit(String action, String targetType, String targetId, Map<String, Object> before,
      Map<String, Object> after, String reason, String actorUserId) {
    auditService.record(AuditEntry.builder()
        .actorUserId(actorUserId)
        .action(action)
        .targetType(targetType)
        .targetId(targetId)
        .before(before)
        .after(after)
        .reason(reason == null ? null : reason.trim())
        .source(AuditSource.ADMIN_UI)
        .build());
  }
}
