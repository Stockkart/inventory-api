package com.inventory.plan.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.config.PlanDefaults;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.domain.repository.UsageRepository;
import com.inventory.plan.rest.dto.plan.RecordUsageRequest;
import com.inventory.plan.rest.dto.plan.UsageResponse;
import com.inventory.plan.rest.mapper.PlanMapper;
import com.inventory.plan.validation.PlanValidator;
import com.inventory.plan.service.ShopProvider.ShopInfo;
import com.inventory.user.service.UserShopMembershipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

@Service
@Slf4j
public class UsageService {

  private static final int MAX_USAGE_MONTHS_RETAINED = 12;

  @Autowired
  private UsageRepository usageRepository;

  @Autowired
  private PlanRepository planRepository;

  @Autowired(required = false)
  private ShopProvider shopProvider;

  @Autowired
  private PlanMapper planMapper;

  @Autowired(required = false)
  private UserShopMembershipService userShopMembershipService;

  @Autowired
  private PlanValidator planValidator;

  public String getCurrentMonthKey() {
    return YearMonth.now().toString();
  }

  @Transactional(readOnly = true)
  public Usage getOrCreateCurrentMonthUsage(String shopId) {
    String month = getCurrentMonthKey();
    return usageRepository.findByShopIdAndMonth(shopId, month)
        .orElseGet(() -> createUsage(shopId, month));
  }

  @Transactional
  public Usage createUsage(String shopId, String month) {
    Usage usage = new Usage();
    usage.setShopId(shopId);
    usage.setMonth(month);
    usage.setBillingAmountUsed(BigDecimal.ZERO);
    usage.setBillCountUsed(0);
    usage.setSmsUsed(0);
    usage.setWhatsappUsed(0);
    usage.setCreatedAt(Instant.now());
    usage.setUpdatedAt(Instant.now());
    return usageRepository.save(usage);
  }

  @Transactional(readOnly = true)
  public UsageResponse getCurrentUsage(String shopId) {
    Usage usage = getOrCreateCurrentMonthUsage(shopId);
    return planMapper.toUsageResponse(usage);
  }

  /**
   * Check if the shop can add a bill with the given amount and count.
   * For Base plan, restricts when limit exceeded.
   */
  @Transactional(readOnly = true)
  public void checkCanAddBill(String shopId, BigDecimal amount, int countDelta) {
    ShopInfo shopInfo = getShopInfo(shopId);
    Plan plan = resolveEffectivePlan(shopInfo);

    Usage usage = getOrCreateCurrentMonthUsage(shopId);

    if (plan.isUnlimited()) {
      return;
    }

    if (plan.getBillingLimit() != null) {
      BigDecimal newTotal = (usage.getBillingAmountUsed() != null ? usage.getBillingAmountUsed() : BigDecimal.ZERO)
          .add(amount != null ? amount : BigDecimal.ZERO);
      if (newTotal.compareTo(plan.getBillingLimit()) > 0) {
        throw new ValidationException(
            "Billing limit reached. Maximum monthly billing amount: " + plan.getBillingLimit());
      }
    }

    if (plan.getBillCountLimit() != null) {
      int newCount = (usage.getBillCountUsed() != null ? usage.getBillCountUsed() : 0) + countDelta;
      if (newCount > plan.getBillCountLimit()) {
        throw new ValidationException(
            "Bill count limit reached. Maximum bills per month: " + plan.getBillCountLimit());
      }
    }
  }

  /**
   * Record usage. Called when a bill is created, SMS sent, etc.
   * Validates limits for non-unlimited plans before recording.
   */
  @Transactional
  public UsageResponse recordUsage(String shopId, RecordUsageRequest request) {
    planValidator.validateRecordUsageRequest(request);

    ShopInfo shopInfo = getShopInfo(shopId);
    Plan plan = resolveEffectivePlan(shopInfo);
    Usage usage = getOrCreateCurrentMonthUsage(shopId);

    if (!plan.isUnlimited()) {
      if (request.getBillingAmount() != null && request.getBillingAmount().signum() > 0) {
        BigDecimal newTotal = (usage.getBillingAmountUsed() != null ? usage.getBillingAmountUsed() : BigDecimal.ZERO)
            .add(request.getBillingAmount());
        if (plan.getBillingLimit() != null && newTotal.compareTo(plan.getBillingLimit()) > 0) {
          throw new ValidationException("Billing limit exceeded. Cannot add this amount.");
        }
      }
      if (request.getBillCount() != null && request.getBillCount() > 0) {
        int newCount = (usage.getBillCountUsed() != null ? usage.getBillCountUsed() : 0) + request.getBillCount();
        if (plan.getBillCountLimit() != null && newCount > plan.getBillCountLimit()) {
          throw new ValidationException("Bill count limit exceeded.");
        }
      }
      if (request.getSmsCount() != null && request.getSmsCount() > 0 && plan.getSmsLimit() != null) {
        int newCount = (usage.getSmsUsed() != null ? usage.getSmsUsed() : 0) + request.getSmsCount();
        if (newCount > plan.getSmsLimit()) {
          throw new ValidationException("SMS limit exceeded.");
        }
      }
      if (request.getWhatsappCount() != null && request.getWhatsappCount() > 0 && plan.getWhatsappLimit() != null) {
        int newCount = (usage.getWhatsappUsed() != null ? usage.getWhatsappUsed() : 0) + request.getWhatsappCount();
        if (newCount > plan.getWhatsappLimit()) {
          throw new ValidationException("WhatsApp limit exceeded.");
        }
      }
    }

    if (request.getBillingAmount() != null && request.getBillingAmount().signum() > 0) {
      usage.setBillingAmountUsed(
          (usage.getBillingAmountUsed() != null ? usage.getBillingAmountUsed() : BigDecimal.ZERO)
              .add(request.getBillingAmount()));
    }
    if (request.getBillCount() != null && request.getBillCount() > 0) {
      usage.setBillCountUsed((usage.getBillCountUsed() != null ? usage.getBillCountUsed() : 0) + request.getBillCount());
    }
    if (request.getSmsCount() != null && request.getSmsCount() > 0) {
      usage.setSmsUsed((usage.getSmsUsed() != null ? usage.getSmsUsed() : 0) + request.getSmsCount());
    }
    if (request.getWhatsappCount() != null && request.getWhatsappCount() > 0) {
      usage.setWhatsappUsed((usage.getWhatsappUsed() != null ? usage.getWhatsappUsed() : 0) + request.getWhatsappCount());
    }
    usage.setUpdatedAt(Instant.now());
    usage = usageRepository.save(usage);

    purgeOldUsage(shopId);
    return planMapper.toUsageResponse(usage);
  }

  private void purgeOldUsage(String shopId) {
    List<Usage> usages = usageRepository.findByShopIdOrderByMonthDesc(shopId, PageRequest.of(0, 100));
    if (usages.size() > MAX_USAGE_MONTHS_RETAINED) {
      for (int i = MAX_USAGE_MONTHS_RETAINED; i < usages.size(); i++) {
        usageRepository.delete(usages.get(i));
      }
    }
  }

  public int getUserCountForShop(String shopId) {
    if (userShopMembershipService == null) {
      return 0;
    }
    return userShopMembershipService.getUserCountForShop(shopId);
  }

  private ShopInfo getShopInfo(String shopId) {
    if (shopProvider == null) {
      throw new ResourceNotFoundException("Shop", "id", shopId);
    }
    return shopProvider.getShop(shopId)
        .orElseThrow(() -> new ResourceNotFoundException("Shop", "id", shopId));
  }

  /**
   * Trial shops get Base plan limits. Paid shops get their plan's limits.
   */
  private Plan resolveEffectivePlan(ShopInfo shopInfo) {
    if (shopInfo.planId() != null && !shopInfo.planId().isBlank()) {
      return planRepository.findById(shopInfo.planId())
          .orElse(PlanDefaults.getBasePlanDefaults());
    }
    return PlanDefaults.getBasePlanDefaults();
  }
}
