package com.inventory.plan.service;

import com.inventory.plan.api.port.ShopPort;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.tx.MongoTx;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.domain.repository.UsageRepository;
import com.inventory.plan.mapper.PlanMapper;
import com.inventory.plan.mapper.PlanTransactionMapper;
import com.inventory.plan.rest.dto.response.PlanResponse;
import com.inventory.plan.rest.dto.response.PlanTransactionResponse;
import com.inventory.plan.rest.dto.response.ShopPlanStatusResponse;
import com.inventory.plan.rest.dto.response.UsageResponse;
import com.inventory.plan.api.port.ShopPort.ShopInfo;
import com.inventory.plan.utils.PlanUtils;
import com.inventory.plan.utils.PlanUtils;
import com.inventory.plan.validation.PlanValidator;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.plan.utils.constants.PlanMetricsConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
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

  @Autowired
  private ShopPort shopProvider;

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
  private MongoTx mongoTx;
  /** Free-trial length for new shops; the same setting ShopService uses at registration. */
  @Value("${plan.trial-days:3}")
  int trialDays = 3;

  @Autowired
  private EntitlementService entitlementService;

  /**
   * List all plans (public - can be called before login for pricing page).
   */
  @Transactional(readOnly = true)
  public List<PlanResponse> listPlans() {
    return effectivePlanResolver.activeCatalogue().stream()
        .map(planMapper::toResponse)
        .map(this::withTrialDays)
        .collect(Collectors.toList());
  }

  @Transactional(readOnly = true)
  public PlanResponse getPlan(String planId) {
    Plan plan = planRepository.findById(planId)
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "id", planId));
    return withTrialDays(planMapper.toResponse(plan));
  }

  /**
   * Refuses a purchase of a different plan while the shop's paid plan still has days left (A2,
   * owner decision 2026-11-21). Renewing the same plan is always allowed; a shop on its trial or
   * with an ended plan may buy any plan. Quote and checkout both call this.
   *
   * @throws BaseException {@code PLAN_CHANGE_NOT_ALLOWED} (409)
   */
  public void requirePlanChangeAllowed(String shopId, Plan plan) {
    ShopInfo shop = getShopInfo(shopId);
    if (hasPaidDaysLeft(shop, Instant.now()) && !isSamePlan(shop.planId(), plan)) {
      throw new BaseException(ErrorCode.PLAN_CHANGE_NOT_ALLOWED, ErrorCode.PLAN_CHANGE_NOT_ALLOWED.getMessage());
    }
  }

  /**
   * When a newly paid term starts: at the current expiry for a renewal of the same plan with days
   * left (so no paid day is lost), else now.
   */
  Instant termStart(ShopInfo shop, Plan plan, Instant now) {
    if (hasPaidDaysLeft(shop, now) && isSamePlan(shop.planId(), plan)) {
      return shop.planExpiryDate();
    }
    return now;
  }

  private static boolean hasPaidDaysLeft(ShopInfo shop, Instant now) {
    return shop.planId() != null && !shop.planId().isBlank()
        && shop.planExpiryDate() != null && shop.planExpiryDate().isAfter(now);
  }

  /** Same plan by id, or by code for a legacy duplicate of the same catalogue plan. */
  private boolean isSamePlan(String currentPlanId, Plan plan) {
    if (currentPlanId == null) {
      return false;
    }
    if (currentPlanId.equals(plan.getId())) {
      return true;
    }
    return plan.getCode() != null && planRepository.findById(currentPlanId)
        .map(current -> plan.getCode().equals(current.getCode()))
        .orElse(false);
  }

  /**
   * Grants the plan an order paid for, in one MongoDB transaction: the plan transaction (unique per
   * order, index {@code paymentOrderId_unique}), the shop's plan fields and its subscription. A
   * retried or parallel fulfilment finds the transaction and grants nothing; if two attempts race,
   * the unique index rejects the second. A renewal of the same plan extends from the current
   * expiry instead of from now.
   */
  public void grantForOrder(PlanPaymentOrder order) {
    try {
      mongoTx.run(MongoTx.PLAN_GRANT, order.getShopId(), () -> grantOnce(order));
    } catch (DuplicateKeyException e) {
      log.info("Plan for order {} was granted by a parallel attempt", order.getId());
    }
  }

  private void grantOnce(PlanPaymentOrder order) {
    if (planTransactionRepository.existsByPaymentOrderId(order.getId())) {
      log.info("Plan for order {} already granted", order.getId());
      return;
    }
    ShopInfo shop = shopProvider.getShop(order.getShopId())
        .orElseThrow(() -> new ResourceNotFoundException("Shop", "id", order.getShopId()));
    Plan plan = planRepository.findById(order.getPlanId())
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "id", order.getPlanId()));

    Instant now = Instant.now();
    Instant termStartsAt = termStart(shop, plan, now);
    if (hasPaidDaysLeft(shop, now) && !isSamePlan(shop.planId(), plan)) {
      // checkout refuses this; only a change made between checkout and payment gets here
      log.warn("Order {} replaces plan {} of shop {} that still had days left until {}",
          order.getId(), shop.planId(), order.getShopId(), shop.planExpiryDate());
    }
    int durationMonths = order.getDurationMonths() != null ? order.getDurationMonths() : 1;
    Instant termEndsAt = PlanUtils.plusMonths(termStartsAt, durationMonths);

    // the claim first: a second attempt fails on the unique index before touching the shop
    PlanTransaction tx = planTransactionMapper.toTransaction(order, plan, termStartsAt, termEndsAt, now);
    planTransactionRepository.insert(tx);
    shopProvider.updatePlan(order.getShopId(), plan.getId(), termEndsAt);
    shopSubscriptionService.recordGrant(new ShopInfo(order.getShopId(), plan.getId(), termEndsAt), order.getId());

    log.info("Granted plan {} to shop {} from {} until {} for order {}",
        plan.getPlanName(), order.getShopId(), termStartsAt, termEndsAt, order.getId());
    MongoTx.afterCommit(() -> metrics.record(
        PlanMetricsConstants.ASSIGNED_TOTAL,
        1,
        "module",
        PlanMetricsConstants.MODULE));
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

    ShopPlanStatusResponse status = planMapper.toShopPlanStatusResponse(
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
    status.setTrialDays(trialDays);
    return status;
  }

  private PlanResponse withTrialDays(PlanResponse response) {
    response.setTrialDays(trialDays);
    return response;
  }

  /**
   * Entitlements are read from the subscription, so a change whose sync failed is not finished: the
   * error propagates and the payment fulfilment step runs again. Reads never write it.
   */
  private void syncSubscription(ShopInfo shopInfo, String sourceOrderId) {
    shopSubscriptionService.sync(shopInfo, sourceOrderId);
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
