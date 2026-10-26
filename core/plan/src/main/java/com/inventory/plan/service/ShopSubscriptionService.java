package com.inventory.plan.service;

import com.inventory.plan.domain.model.ShopSubscription;
import com.inventory.plan.domain.model.SubscriptionStatus;
import com.inventory.plan.domain.repository.ShopSubscriptionRepository;
import com.inventory.plan.service.ShopProvider.ShopInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
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
  public ShopSubscription sync(ShopInfo shop, String sourceOrderId) {
    Instant now = Instant.now();
    SubscriptionStatus status = deriveStatus(shop.planId(), shop.planExpiryDate(), now);
    Optional<ShopSubscription> existing = shopSubscriptionRepository.findById(shop.shopId());

    if (existing.isPresent() && matches(existing.get(), shop, status)
        && (sourceOrderId == null || sourceOrderId.equals(existing.get().getSourceOrderId()))) {
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
    if (sourceOrderId != null) {
      subscription.setSourceOrderId(sourceOrderId);
    }
    subscription.setUpdatedAt(now);
    return shopSubscriptionRepository.save(subscription);
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

  private static boolean matches(ShopSubscription current, ShopInfo shop, SubscriptionStatus status) {
    String planId = StringUtils.hasText(shop.planId()) ? shop.planId() : null;
    return Objects.equals(current.getPlanId(), planId)
        && current.getStatus() == status
        && Objects.equals(current.getExpiresAt(), shop.planExpiryDate());
  }
}
