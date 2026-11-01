package com.inventory.plan.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.domain.repository.UsageRepository;
import com.inventory.plan.mapper.PlanMapper;
import com.inventory.plan.mapper.PlanTransactionMapper;
import com.inventory.plan.rest.dto.request.AssignPlanRequest;
import com.inventory.plan.rest.dto.response.PlanResponse;
import com.inventory.plan.rest.dto.response.PlanTransactionResponse;
import com.inventory.plan.rest.dto.response.ShopPlanStatusResponse;
import com.inventory.plan.rest.dto.response.UsageResponse;
import com.inventory.plan.service.ShopProvider.ShopInfo;
import com.inventory.plan.utils.PlanUtils;
import com.inventory.plan.utils.PlanUtils;
import com.inventory.plan.validation.PlanValidator;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.plan.utils.constants.PlanMetricsConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;

@Service
@Slf4j
public class PlanService {

  @Autowired
  private PlanRepository planRepository;

  @Autowired(required = false)
  private ShopProvider shopProvider;

  @Autowired
  private UsageRepository usageRepository;

  @Autowired
  private PlanTransactionRepository planTransactionRepository;

  @Autowired
  private PlanMapper planMapper;

  @Autowired
  private PlanTransactionMapper planTransactionMapper;

  @Autowired
  private PlanValidator planValidator;

  @Autowired
  private UsageService usageService;

  @Autowired
  private EffectivePlanResolver effectivePlanResolver;

  @Autowired
  private MetricsWrapper metrics;

  @Autowired
  private ShopSubscriptionService shopSubscriptionService;

  @Autowired
  private EntitlementService entitlementService;

  /**
   * List all plans (public - can be called before login for pricing page).
   */
  @Transactional(readOnly = true)
  public List<PlanResponse> listPlans() {
    return effectivePlanResolver.activeCatalogue().stream()
        .map(planMapper::toResponse)
        .collect(Collectors.toList());
  }

  @Transactional(readOnly = true)
  public PlanResponse getPlan(String planId) {
    Plan plan = planRepository.findById(planId)
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "id", planId));
    return planMapper.toResponse(plan);
  }

  /**
   * Assign plan to shop (called after payment). Updates shop's planId and expiryDate.
   */
  @Transactional
  public PlanResponse assignPlan(String shopId, AssignPlanRequest request) {
    planValidator.validateAssignPlanRequest(shopId, request);

    if (shopProvider == null) {
      throw new ResourceNotFoundException("Shop", "id", shopId);
    }
    shopProvider.getShop(shopId)
        .orElseThrow(() -> new ResourceNotFoundException("Shop", "id", shopId));

    Plan plan = planRepository.findById(request.getPlanId())
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "id", request.getPlanId()));

    int durationMonths = request.getDurationMonths() != null ? request.getDurationMonths() : 1;
    Instant expiryDate = PlanUtils.plusMonths(Instant.now(), durationMonths);
    shopProvider.updatePlan(shopId, plan.getId(), expiryDate);
    syncSubscription(new ShopInfo(shopId, plan.getId(), expiryDate), request.getPaymentOrderId());

    PlanTransaction tx = planTransactionMapper.toTransaction(shopId, plan, request);
    planTransactionRepository.save(tx);

    log.info("Assigned plan {} to shop {} until {} (tx: {})", plan.getPlanName(), shopId, expiryDate, tx.getId());
    metrics.record(
        PlanMetricsConstants.ASSIGNED_TOTAL,
        1,
        "module",
        PlanMetricsConstants.MODULE);
    return planMapper.toResponse(plan);
  }

  /**
   * Grants the plan an order paid for. Idempotent: an order that already has its transaction is
   * not granted again, so a retried fulfilment cannot extend the term twice.
   */
  public void grantForOrder(PlanPaymentOrder order) {
    if (planTransactionRepository.existsByPaymentOrderId(order.getId())) {
      log.info("Plan for order {} already granted", order.getId());
      return;
    }
    if (shopProvider == null) {
      throw new ResourceNotFoundException("Shop", "id", order.getShopId());
    }
    shopProvider.getShop(order.getShopId())
        .orElseThrow(() -> new ResourceNotFoundException("Shop", "id", order.getShopId()));
    Plan plan = planRepository.findById(order.getPlanId())
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "id", order.getPlanId()));

    Instant termStartsAt = Instant.now();
    int durationMonths = order.getDurationMonths() != null ? order.getDurationMonths() : 1;
    Instant termEndsAt = PlanUtils.plusMonths(termStartsAt, durationMonths);
    shopProvider.updatePlan(order.getShopId(), plan.getId(), termEndsAt);
    syncSubscription(new ShopInfo(order.getShopId(), plan.getId(), termEndsAt), order.getId());

    PlanTransaction tx = planTransactionMapper.toTransaction(order, plan, termStartsAt, termEndsAt);
    planTransactionRepository.save(tx);

    log.info("Granted plan {} to shop {} until {} for order {}",
        plan.getPlanName(), order.getShopId(), termEndsAt, order.getId());
    metrics.record(
        PlanMetricsConstants.ASSIGNED_TOTAL,
        1,
        "module",
        PlanMetricsConstants.MODULE);
  }

  /**
   * Takes back the term a refunded order granted. If the shop still holds that term, it falls back to
   * the latest earlier term that has not ended or been refunded, else its plan expires now. A later
   * purchase that replaced the term is left alone. Safe to repeat.
   */
  public void revokeForOrder(PlanPaymentOrder order) {
    Optional<PlanTransaction> granted = planTransactionRepository.findFirstByPaymentOrderId(order.getId());
    if (granted.isEmpty() || granted.get().getRefundedAt() != null) {
      return;
    }
    PlanTransaction tx = granted.get();
    Instant now = Instant.now();
    ShopInfo shop = getShopInfo(order.getShopId());
    if (Objects.equals(shop.planId(), tx.getPlanId()) && Objects.equals(shop.planExpiryDate(), tx.getTermEndsAt())) {
      Optional<PlanTransaction> previous = planTransactionRepository
          .findByShopId(order.getShopId(), Sort.by(Sort.Direction.DESC, "createdAt")).stream()
          .filter(t -> !t.getId().equals(tx.getId()))
          .filter(t -> t.getRefundedAt() == null)
          .filter(t -> t.getCreatedAt() != null && tx.getCreatedAt() != null && t.getCreatedAt().isBefore(tx.getCreatedAt()))
          .filter(t -> t.getTermEndsAt() != null && t.getTermEndsAt().isAfter(now))
          .findFirst();
      String planId = previous.map(PlanTransaction::getPlanId).orElse(tx.getPlanId());
      Instant expiry = previous.map(PlanTransaction::getTermEndsAt).orElse(now);
      shopProvider.updatePlan(order.getShopId(), planId, expiry);
      syncSubscription(new ShopInfo(order.getShopId(), planId, expiry),
          previous.map(PlanTransaction::getPaymentOrderId).orElse(null));
      log.info("Revoked plan term of order {} for shop {}; plan {} until {}", order.getId(), order.getShopId(),
          planId, expiry);
    } else {
      log.info("Order {} term was already replaced for shop {}; plan left as is", order.getId(), order.getShopId());
    }
    tx.setRefundedAt(now);
    planTransactionRepository.save(tx);
    entitlementService.invalidate(order.getShopId());
  }

  /**
   * List plan payment transactions for a shop.
   */
  @Transactional(readOnly = true)
  public List<PlanTransactionResponse> listPlanTransactions(String shopId) {
    return planTransactionRepository.findByShopId(shopId, Sort.by(Sort.Direction.DESC, "createdAt"))
        .stream()
        .map(planTransactionMapper::toResponse)
        .collect(Collectors.toList());
  }

  /**
   * Get shop's plan status: plan, trial/expired, usage, suggested upsell plan.
   */
  @Transactional(readOnly = true)
  public ShopPlanStatusResponse getShopPlanStatus(String shopId) {
    ShopInfo shopInfo = getShopInfo(shopId);
    syncSubscription(shopInfo, null);

    Plan plan = null;
    if (shopInfo.planId() != null && !shopInfo.planId().isBlank()) {
      plan = planRepository.findById(shopInfo.planId()).orElse(null);
    }
    boolean trial = (plan == null && shopInfo.planExpiryDate() != null);
    boolean planExpired = PlanUtils.isExpired(shopInfo.planExpiryDate());
    boolean trialExpired = trial && planExpired;

    Plan effectivePlan = plan != null ? plan : effectivePlanResolver.trialPlan();
    Usage usage = usageService.getOrCreateCurrentMonthUsage(shopId);
    UsageResponse usageResponse = planMapper.toUsageResponse(usage);

    PlanResponse suggestedPlan = getSuggestedPlan(shopInfo);

    int userCount = usageService.getUserCountForShop(shopId);
    int userLimit = effectivePlan.getUserLimit() != null ? effectivePlan.getUserLimit() : Integer.MAX_VALUE;
    boolean userLimitReached = userCount >= userLimit;

    var limits = planValidator.computeLimitsReached(effectivePlan, usage);

    return planMapper.toShopPlanStatusResponse(
        shopId,
        shopInfo.planId(),
        plan != null ? planMapper.toResponse(plan) : null,
        shopInfo.planExpiryDate(),
        trial,
        trialExpired,
        planExpired,
        usageResponse,
        suggestedPlan,
        limits,
        userLimitReached);
  }

  /**
   * Shop fields remain authoritative until read paths move to ShopSubscription, so a failed sync
   * must not fail the caller; the next sync or the backfill repairs it.
   */
  private void syncSubscription(ShopInfo shopInfo, String sourceOrderId) {
    try {
      shopSubscriptionService.sync(shopInfo, sourceOrderId);
    } catch (RuntimeException e) {
      log.warn("Shop subscription sync failed for shop {}: {}", shopInfo.shopId(), e.getMessage());
    }
  }

  private ShopInfo getShopInfo(String shopId) {
    if (shopProvider == null) {
      throw new ResourceNotFoundException("Shop", "id", shopId);
    }
    return shopProvider.getShop(shopId)
        .orElseThrow(() -> new ResourceNotFoundException("Shop", "id", shopId));
  }

  /**
   * Get suggested next plan (via linkedId) for upsell.
   */
  @Transactional(readOnly = true)
  public PlanResponse getSuggestedPlan(String shopId) {
    ShopInfo shopInfo = getShopInfo(shopId);
    return getSuggestedPlan(shopInfo);
  }

  private PlanResponse getSuggestedPlan(ShopInfo shopInfo) {
    Plan current = null;
    if (shopInfo.planId() != null && !shopInfo.planId().isBlank()) {
      current = planRepository.findById(shopInfo.planId()).orElse(null);
    }
    if (current == null) {
      current = effectivePlanResolver.findTrialPlan().orElse(null);
    }
    if (current != null && current.getLinkedId() != null) {
      Optional<Plan> next = planRepository.findById(current.getLinkedId());
      return next.map(planMapper::toResponse).orElse(null);
    }
    return null;
  }
}
