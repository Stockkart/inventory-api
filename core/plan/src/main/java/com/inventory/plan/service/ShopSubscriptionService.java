package com.inventory.plan.service;

import com.inventory.plan.domain.model.PlanTermsSnapshot;
import com.inventory.plan.domain.model.ShopSubscription;
import com.inventory.plan.domain.model.SubscriptionStatus;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.domain.repository.ShopSubscriptionRepository;
import com.inventory.plan.api.port.ShopPort.ShopInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps {@link ShopSubscription} in step with the shop's plan fields and expires lapsed ones.
 */
@Service
@Slf4j
public class ShopSubscriptionService {

  static final List<SubscriptionStatus> EXPIRABLE = List.of(SubscriptionStatus.TRIAL, SubscriptionStatus.ACTIVE);

  @Autowired
  private ShopSubscriptionRepository shopSubscriptionRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private EntitlementService entitlementService;

  @Autowired
  private PlanRepository planRepository;

  /**
   * Auto index creation is off in this application, so the sweep index is created here.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void ensureIndexes() {
    mongoTemplate.indexOps(ShopSubscription.class)
        .ensureIndex(new Index().on("status", Sort.Direction.ASC).on("expiresAt", Sort.Direction.ASC));
  }

  /**
   * Writes the subscription derived from the shop's plan fields. No write when nothing changed.
   */
  public ShopSubscription sync(ShopInfo shop, String planPaymentId) {
    Instant now = Instant.now();
    SubscriptionStatus status = deriveStatus(shop.planId(), shop.planExpiryDate(), now);
    Optional<ShopSubscription> existing = shopSubscriptionRepository.findById(shop.shopId());

    if (existing.isPresent() && matches(existing.get(), shop, status)
        && (planPaymentId == null || planPaymentId.equals(existing.get().getPlanPaymentId()))) {
      return existing.get();
    }

    ShopSubscription subscription = existing.orElseGet(() -> ShopSubscription.builder()
        .id(shop.shopId())
        .shopId(shop.shopId())
        .createdAt(now)
        .build());
    subscription.setPlanId(StringUtils.hasText(shop.planId()) ? shop.planId() : null);
    subscription.setStatus(status);
    subscription.setExpiresAt(shop.planExpiryDate());
    if (planPaymentId != null) {
      subscription.setPlanPaymentId(planPaymentId);
    }
    subscription.setUpdatedAt(now);
    ShopSubscription saved = shopSubscriptionRepository.save(subscription);
    entitlementService.invalidate(shop.shopId());
    return saved;
  }

  /**
   * Records the term a paid order granted, never moving a term of the same plan backwards: when two
   * grants race, or a stale one arrives late, the later end date stays. A grant of another plan, or
   * without an end date, always applies. The check is a read (exact inside a {@code MongoTx}
   * transaction) and the write is conditional on the same rule, so a race outside one cannot slip
   * through either. Use {@link #sync} for changes that may shorten the term (refund revocation).
   */
  public ShopSubscription recordGrant(ShopInfo shop, String planPaymentId) {
    Instant now = Instant.now();
    SubscriptionStatus status = deriveStatus(shop.planId(), shop.planExpiryDate(), now);
    Instant expiresAt = shop.planExpiryDate();
    String planId = StringUtils.hasText(shop.planId()) ? shop.planId() : null;
    Optional<ShopSubscription> current = shopSubscriptionRepository.findById(shop.shopId());
    if (current.isPresent() && endsLaterOnSamePlan(current.get(), planId, expiresAt)) {
      log.warn("Subscription of shop {} already ends after {}; order {} left it unchanged",
          shop.shopId(), expiresAt, planPaymentId);
      return current.get();
    }
    Criteria byShop = Criteria.where("_id").is(shop.shopId());
    Query query = new Query(expiresAt == null ? byShop
        : byShop.orOperator(Criteria.where("expiresAt").is(null), Criteria.where("expiresAt").lte(expiresAt),
            Criteria.where("planId").ne(planId)));
    Update update = new Update()
        .set("shopId", shop.shopId())
        .set("planId", planId)
        .set("status", status)
        .set("expiresAt", expiresAt)
        .set("planPaymentId", planPaymentId)
        .set("terms", termsOf(shop.planId()))
        .set("updatedAt", now)
        .setOnInsert("createdAt", now);
    try {
      ShopSubscription saved = mongoTemplate.findAndModify(query, update,
          FindAndModifyOptions.options().upsert(true).returnNew(true), ShopSubscription.class);
      entitlementService.invalidate(shop.shopId());
      return saved;
    } catch (DuplicateKeyException e) {
      // the stored subscription already ends later: keep it
      log.warn("Subscription of shop {} already ends after {}; order {} left it unchanged",
          shop.shopId(), expiresAt, planPaymentId);
      return shopSubscriptionRepository.findById(shop.shopId()).orElse(null);
    }
  }

  /**
   * Marks every trial or active subscription past its expiry as EXPIRED. A single conditional
   * update, so concurrent runs on several instances cannot conflict.
   */
  public long expireLapsed(Instant now) {
    Query query = new Query(Criteria.where("status").in(EXPIRABLE).and("expiresAt").lt(now));
    Update update = new Update().set("status", SubscriptionStatus.EXPIRED).set("updatedAt", now);
    long expired = mongoTemplate.updateMulti(query, update, ShopSubscription.class).getModifiedCount();
    if (expired > 0) {
      log.info("Expired {} shop subscription(s)", expired);
      entitlementService.invalidateAll();
    }
    return expired;
  }

  /**
   * Same rule as the shop's plan status: expired only once the expiry instant has passed.
   */
  static SubscriptionStatus deriveStatus(String planId, Instant expiresAt, Instant now) {
    if (expiresAt != null && expiresAt.isBefore(now)) {
      return SubscriptionStatus.EXPIRED;
    }
    return StringUtils.hasText(planId) ? SubscriptionStatus.ACTIVE : SubscriptionStatus.TRIAL;
  }

  /** The granted plan's features and limits now, kept on the term (A12); null when unknown. */
  PlanTermsSnapshot termsOf(String planId) {
    if (!StringUtils.hasText(planId)) {
      return null;
    }
    return planRepository.findById(planId)
        .map(plan -> PlanTermsSnapshot.builder()
            .planId(plan.getId())
            .planCode(plan.getCode())
            .features(plan.getFeatures())
            .userLimit(plan.getUserLimit())
            .ocrLimit(plan.getOcrLimit())
            .unlimited(plan.isUnlimited())
            .build())
        .orElse(null);
  }

  private static boolean endsLaterOnSamePlan(ShopSubscription current, String planId, Instant expiresAt) {
    return expiresAt != null && current.getExpiresAt() != null && current.getExpiresAt().isAfter(expiresAt)
        && Objects.equals(current.getPlanId(), planId);
  }

  private static boolean matches(ShopSubscription current, ShopInfo shop, SubscriptionStatus status) {
    String planId = StringUtils.hasText(shop.planId()) ? shop.planId() : null;
    return Objects.equals(current.getPlanId(), planId)
        && current.getStatus() == status
        && Objects.equals(current.getExpiresAt(), shop.planExpiryDate());
  }
}
