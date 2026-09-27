package com.inventory.plan.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.domain.repository.UsageRepository;
import com.inventory.plan.mapper.PlanMapper;
import com.inventory.plan.mapper.UsageMapper;
import com.inventory.plan.rest.dto.request.RecordUsageRequest;
import com.inventory.plan.rest.dto.response.UsageResponse;
import com.inventory.plan.service.ShopProvider.ShopInfo;
import com.inventory.plan.utils.PlanUtils;
import com.inventory.plan.utils.constants.PlanConstants;
import com.inventory.plan.validation.PlanValidator;
import com.inventory.user.service.UserShopMembershipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
public class UsageService {

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

  @Autowired
  private UsageMapper usageMapper;

  @Autowired
  private EffectivePlanResolver effectivePlanResolver;

  @Autowired
  private MongoTemplate mongoTemplate;

  public String getCurrentMonthKey() {
    return PlanUtils.getCurrentMonthKey();
  }

  @Transactional(readOnly = true)
  public Usage getOrCreateCurrentMonthUsage(String shopId) {
    String month = getCurrentMonthKey();
    return usageRepository.findByShopIdAndMonth(shopId, month)
        .orElseGet(() -> createUsage(shopId, month));
  }

  @Transactional
  public Usage createUsage(String shopId, String month) {
    Usage usage = usageMapper.toUsage(shopId, month);
    return usageRepository.save(usage);
  }

  @Transactional(readOnly = true)
  public UsageResponse getCurrentUsage(String shopId) {
    Usage usage = getOrCreateCurrentMonthUsage(shopId);
    return planMapper.toUsageResponse(usage);
  }

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
        throw new com.inventory.common.exception.ValidationException(
            "Billing limit reached. Maximum monthly billing amount: " + plan.getBillingLimit());
      }
    }

    if (plan.getBillCountLimit() != null) {
      int newCount = (usage.getBillCountUsed() != null ? usage.getBillCountUsed() : 0) + countDelta;
      if (newCount > plan.getBillCountLimit()) {
        throw new com.inventory.common.exception.ValidationException(
            "Bill count limit reached. Maximum bills per month: " + plan.getBillCountLimit());
      }
    }
  }

  @Transactional
  public UsageResponse recordUsage(String shopId, RecordUsageRequest request) {
    planValidator.validateRecordUsageRequest(request);

    ShopInfo shopInfo = getShopInfo(shopId);
    Plan plan = resolveEffectivePlan(shopInfo);
    Usage usage = getOrCreateCurrentMonthUsage(shopId);

    planValidator.validateUsageWithinLimits(plan, usage, request);

    usageMapper.applyRecordUsage(usage, request);
    usage.setUpdatedAt(Instant.now());
    usage = usageRepository.save(usage);

    purgeOldUsage(shopId);
    return planMapper.toUsageResponse(usage);
  }

  /**
   * Counts one scanned invoice against this month's included OCR quota. Atomic, so concurrent
   * scans are all counted.
   */
  public void recordOcrUsage(String shopId) {
    Usage usage = getOrCreateCurrentMonthUsage(shopId);
    mongoTemplate.updateFirst(
        new Query(Criteria.where("_id").is(usage.getId())),
        new Update().inc("ocrUsed", 1).set("updatedAt", Instant.now()),
        Usage.class);
  }

  /**
   * Counts one scan against the included quota only if the quota has room, in one conditional
   * write. Returns false when this month's included scans are used up.
   */
  public boolean recordIncludedOcrUnit(String shopId, int monthlyLimit) {
    if (monthlyLimit <= 0) {
      return false;
    }
    Usage usage = getOrCreateCurrentMonthUsage(shopId);
    Query query = new Query(Criteria.where("_id").is(usage.getId()).orOperator(
        Criteria.where("ocrUsed").lt(monthlyLimit),
        Criteria.where("ocrUsed").is(null)));
    return mongoTemplate.updateFirst(query,
        new Update().inc("ocrUsed", 1).set("updatedAt", Instant.now()),
        Usage.class).getModifiedCount() == 1;
  }

  private void purgeOldUsage(String shopId) {
    List<Usage> usages = usageRepository.findByShopIdOrderByMonthDesc(shopId, PageRequest.of(0, 100));
    if (usages.size() > PlanConstants.MAX_USAGE_MONTHS_RETAINED) {
      for (int i = PlanConstants.MAX_USAGE_MONTHS_RETAINED; i < usages.size(); i++) {
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

  private Plan resolveEffectivePlan(ShopInfo shopInfo) {
    return effectivePlanResolver.resolve(shopInfo.planId());
  }
}
