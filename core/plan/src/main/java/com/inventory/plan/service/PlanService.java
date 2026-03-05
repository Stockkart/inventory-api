package com.inventory.plan.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.plan.config.PlanDefaults;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.domain.repository.UsageRepository;
import com.inventory.plan.rest.dto.plan.AssignPlanRequest;
import com.inventory.plan.rest.dto.plan.PlanResponse;
import com.inventory.plan.rest.dto.plan.PlanTransactionResponse;
import com.inventory.plan.rest.dto.plan.ShopPlanStatusResponse;
import com.inventory.plan.rest.dto.plan.UsageResponse;
import com.inventory.plan.rest.mapper.PlanMapper;
import com.inventory.plan.rest.mapper.PlanTransactionMapper;
import com.inventory.plan.service.ShopProvider.ShopInfo;
import com.inventory.plan.validation.PlanValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    Instant expiryDate = Instant.now().plus(request.getDurationMonths() != null ? request.getDurationMonths() : 1, ChronoUnit.MONTHS);
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
        .map(this::toTransactionResponse)
        .collect(Collectors.toList());
  }

  private PlanTransactionResponse toTransactionResponse(PlanTransaction tx) {
    return new PlanTransactionResponse(
        tx.getId(),
        tx.getShopId(),
        tx.getPlanId(),
        tx.getPlanName(),
        tx.getAmount(),
        tx.getDurationMonths(),
        tx.getPaymentMethod(),
        tx.getCreatedAt()
    );
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
    boolean trialExpired = trial && shopInfo.planExpiryDate() != null && shopInfo.planExpiryDate().isBefore(Instant.now());

    Plan effectivePlan = plan != null ? plan : PlanDefaults.getBasePlanDefaults();
    Usage usage = usageService.getOrCreateCurrentMonthUsage(shopId);
    UsageResponse usageResponse = planMapper.toUsageResponse(usage);

    PlanResponse suggestedPlan = getSuggestedPlan(shopInfo);

    int userCount = usageService.getUserCountForShop(shopId);
    int userLimit = effectivePlan.getUserLimit() != null ? effectivePlan.getUserLimit() : Integer.MAX_VALUE;
    boolean userLimitReached = userCount >= userLimit;

    boolean billingLimitReached = false;
    boolean billCountLimitReached = false;
    boolean smsLimitReached = false;
    boolean whatsappLimitReached = false;
    if (!effectivePlan.isUnlimited()) {
      if (effectivePlan.getBillingLimit() != null && usage.getBillingAmountUsed() != null) {
        billingLimitReached = usage.getBillingAmountUsed().compareTo(effectivePlan.getBillingLimit()) >= 0;
      }
      if (effectivePlan.getBillCountLimit() != null && usage.getBillCountUsed() != null) {
        billCountLimitReached = usage.getBillCountUsed() >= effectivePlan.getBillCountLimit();
      }
      if (effectivePlan.getSmsLimit() != null && effectivePlan.getSmsLimit() > 0 && usage.getSmsUsed() != null) {
        smsLimitReached = usage.getSmsUsed() >= effectivePlan.getSmsLimit();
      }
      if (effectivePlan.getWhatsappLimit() != null && effectivePlan.getWhatsappLimit() > 0 && usage.getWhatsappUsed() != null) {
        whatsappLimitReached = usage.getWhatsappUsed() >= effectivePlan.getWhatsappLimit();
      }
    }

    return new ShopPlanStatusResponse(
        shopId,
        shopInfo.planId(),
        plan != null ? planMapper.toResponse(plan) : null,
        shopInfo.planExpiryDate(),
        trial,
        trialExpired,
        usageResponse,
        suggestedPlan,
        billingLimitReached,
        billCountLimitReached,
        smsLimitReached,
        whatsappLimitReached,
        userLimitReached
    );
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
