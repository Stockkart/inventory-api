package com.inventory.plan.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.plan.domain.model.Plan;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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

  @Value("${plan.trial-days:3}")
  private int trialDays;

  /**
   * List all plans (public - can be called before login for pricing page).
   */
  @Transactional(readOnly = true)
  public List<PlanResponse> listPlans() {
    return planRepository.findAllByOrderByPriceAsc().stream()
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

    PlanTransaction tx = planTransactionMapper.toTransaction(shopId, plan, request);
    planTransactionRepository.save(tx);

    log.info("Assigned plan {} to shop {} until {} (tx: {})", plan.getPlanName(), shopId, expiryDate, tx.getId());
    return planMapper.toResponse(plan);
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

    Plan effectivePlan = plan != null ? plan
        : planRepository.findByPlanName("Base")
            .orElseThrow(() -> new ResourceNotFoundException("Plan", "name", "Base"));
    Usage usage = usageService.getOrCreateCurrentMonthUsage(shopId);
    UsageResponse usageResponse = planMapper.toUsageResponse(usage);

    PlanResponse suggestedPlan = getSuggestedPlan(shopInfo);

    int userCount = usageService.getUserCountForShop(shopId);
    int userLimit = effectivePlan.getUserLimit() != null ? effectivePlan.getUserLimit() : Integer.MAX_VALUE;
    boolean userLimitReached = userCount >= userLimit;

    var limits = planValidator.computeLimitsReached(effectivePlan, usage);
    boolean ocrLimitReached = isOcrLimitReached(effectivePlan, usage);

    ShopPlanStatusResponse response = planMapper.toShopPlanStatusResponse(
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

    response.setOcrLimitReached(ocrLimitReached);
    response.setUpgradePlan(resolveUpgradePlan(plan));
    if (trial) {
      response.setTrialDaysTotal(trialDays);
      response.setTrialDaysRemaining(PlanUtils.wholeDaysUntil(shopInfo.planExpiryDate()));
    }
    return response;
  }

  /**
   * Next tier up, following {@code linkedId}.
   *
   * <p>Returns null when the shop is on the top tier, or when the link dangles — a catalog edit can
   * leave a {@code linkedId} pointing at a deleted plan, and a missing upsell is better than a 500.
   */
  private PlanResponse resolveUpgradePlan(Plan plan) {
    if (plan == null || plan.getLinkedId() == null || plan.getLinkedId().isBlank()) {
      return null;
    }
    return planRepository.findById(plan.getLinkedId())
        .map(planMapper::toResponse)
        .orElse(null);
  }

  /** True when the plan caps OCR and the shop has consumed its monthly allowance. */
  private boolean isOcrLimitReached(Plan effectivePlan, Usage usage) {
    Integer limit = effectivePlan.getOcrInvoiceLimit();
    if (effectivePlan.isUnlimited() || limit == null) {
      return false;
    }
    int used = usage.getOcrInvoicesUsed() != null ? usage.getOcrInvoicesUsed() : 0;
    return used >= limit;
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
      current = planRepository.findAllByOrderByPriceAsc().stream().findFirst().orElse(null);
    }
    if (current != null && current.getLinkedId() != null) {
      Optional<Plan> next = planRepository.findById(current.getLinkedId());
      return next.map(planMapper::toResponse).orElse(null);
    }
    return null;
  }
}
