package com.inventory.plan.service;

import com.inventory.plan.domain.model.EntitlementSource;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.ShopEntitlements;
import com.inventory.plan.domain.model.ShopSubscription;
import com.inventory.plan.domain.model.SubscriptionStatus;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.domain.repository.ShopSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a shop's entitlements from its subscription and plan, with a short per-instance cache.
 *
 * <p>Writers that change a shop's plan call {@link #invalidate(String)}; other instances converge
 * within the TTL.
 */
@Service
@Slf4j
public class EntitlementService {

  @Autowired
  private ShopSubscriptionRepository shopSubscriptionRepository;

  @Autowired(required = false)
  private ShopProvider shopProvider;

  @Autowired
  private PlanRepository planRepository;

  @Autowired
  private EffectivePlanResolver effectivePlanResolver;

  @Value("${plan.entitlements.cache-ttl-seconds:60}")
  long cacheTtlSeconds = 60;

  Clock clock = Clock.systemUTC();

  private final ConcurrentHashMap<String, CachedEntitlements> cache = new ConcurrentHashMap<>();

  public ShopEntitlements resolve(String shopId) {
    Instant now = clock.instant();
    CachedEntitlements cached = cache.get(shopId);
    if (cached != null && now.isBefore(cached.expiresAt())) {
      return cached.value();
    }
    ShopEntitlements resolved = compute(shopId, now);
    cache.put(shopId, new CachedEntitlements(resolved, now.plusSeconds(cacheTtlSeconds)));
    return resolved;
  }

  public void invalidate(String shopId) {
    if (shopId != null) {
      cache.remove(shopId);
    }
  }

  public void invalidateAll() {
    cache.clear();
  }

  ShopEntitlements compute(String shopId, Instant now) {
    SubscriptionView subscription = currentSubscription(shopId, now);
    if (subscription.isPaidAndLive(now)) {
      Optional<Plan> plan = planRepository.findById(subscription.planId());
      if (plan.isPresent()) {
        return fromPlan(shopId, plan.get(), EntitlementSource.PLAN, subscription.expiresAt());
      }
      log.warn("Shop {} subscribes to missing plan {}; using trial entitlements", shopId, subscription.planId());
    }
    return effectivePlanResolver.findTrialPlan()
        .map(trial -> fromPlan(shopId, trial, EntitlementSource.TRIAL, subscription.expiresAt()))
        .orElseGet(() -> new ShopEntitlements(
            shopId, null, null, EntitlementSource.TRIAL, Set.of(), null, null, subscription.expiresAt()));
  }

  private SubscriptionView currentSubscription(String shopId, Instant now) {
    Optional<ShopSubscription> stored = shopSubscriptionRepository.findById(shopId);
    if (stored.isPresent()) {
      ShopSubscription s = stored.get();
      return new SubscriptionView(s.getPlanId(), s.getStatus(), s.getExpiresAt());
    }
    if (shopProvider == null) {
      return new SubscriptionView(null, SubscriptionStatus.TRIAL, null);
    }
    return shopProvider.getShop(shopId)
        .map(shop -> new SubscriptionView(
            shop.planId(),
            ShopSubscriptionService.deriveStatus(shop.planId(), shop.planExpiryDate(), now),
            shop.planExpiryDate()))
        .orElseGet(() -> new SubscriptionView(null, SubscriptionStatus.TRIAL, null));
  }

  private static ShopEntitlements fromPlan(String shopId, Plan plan, EntitlementSource source, Instant expiresAt) {
    if (plan.getCode() == null) {
      return new ShopEntitlements(shopId, plan.getId(), null, EntitlementSource.LEGACY_GRANDFATHERED,
          Set.of(), null, null, expiresAt);
    }
    boolean unlimited = plan.isUnlimited();
    return new ShopEntitlements(
        shopId,
        plan.getId(),
        plan.getCode(),
        source,
        plan.getFeatures(),
        unlimited ? null : plan.getUserLimit(),
        unlimited ? null : plan.getOcrLimit(),
        expiresAt);
  }

  private record SubscriptionView(String planId, SubscriptionStatus status, Instant expiresAt) {
    boolean isPaidAndLive(Instant now) {
      return status == SubscriptionStatus.ACTIVE
          && StringUtils.hasText(planId)
          && (expiresAt == null || expiresAt.isAfter(now));
    }
  }

  private record CachedEntitlements(ShopEntitlements value, Instant expiresAt) {}
}
