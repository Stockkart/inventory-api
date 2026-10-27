package com.inventory.plan.service;

import com.inventory.plan.api.UsageGate;
import com.inventory.plan.api.port.ShopPort;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.domain.repository.UsageRepository;
import com.inventory.plan.mapper.PlanMapper;
import com.inventory.plan.mapper.UsageMapper;
import com.inventory.plan.rest.dto.request.RecordUsageRequest;
import com.inventory.plan.rest.dto.response.UsageResponse;
import com.inventory.plan.api.port.ShopPort.ShopInfo;
import com.inventory.plan.utils.PlanUtils;
import com.inventory.plan.utils.constants.PlanConstants;
import com.inventory.plan.validation.PlanValidator;
import com.inventory.user.service.UserShopMembershipService;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.Decimal128;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
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
public class UsageService implements UsageGate {

  @Autowired
  private UsageRepository usageRepository;

  @Autowired
  private PlanRepository planRepository;

  @Autowired
  private ShopPort shopProvider;

  @Autowired
  private PlanMapper planMapper;

  @Autowired(required = false)
  private UserShopMembershipService userShopMembershipService;

  @Autowired
  private PlanValidator planValidator;

  @Autowired
  private UsageMapper usageMapper;

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
    Usage saved = usageRepository.save(usage);
    // a new month is the only time an old one can fall out of the retained window
    purgeOldUsage(shopId);
    return saved;
  }

  @Transactional(readOnly = true)
  public UsageResponse getCurrentUsage(String shopId) {
    Usage usage = getOrCreateCurrentMonthUsage(shopId);
    return planMapper.toUsageResponse(usage);
  }

  @Override
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

    return planMapper.toUsageResponse(increment(shopId, request));
  }

  /**
   * Counts one completed bill against this month's usage, atomically.
   *
   * <p>For checkout, which has already run {@link #checkCanAddBill} for this bill: no second read
   * and no second limit check (the bill is already saved when this runs, so failing here could
   * only leave a completed bill uncounted). Two bills completed at the same moment are both
   * counted; read-modify-save used to lose one.
   */
  @Override
  public void recordCompletedBill(String shopId, BigDecimal amount) {
    RecordUsageRequest request = new RecordUsageRequest();
    request.setBillingAmount(amount != null ? amount : BigDecimal.ZERO);
    request.setBillCount(1);
    increment(shopId, request);
  }

  /** $inc the request's positive deltas on this month's record (created if missing); returns it. */
  private Usage increment(String shopId, RecordUsageRequest request) {
    String month = getCurrentMonthKey();
    // millisecond precision, as stored, so "created by this call" can be told from the result
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Update update = new Update().set("updatedAt", now)
        .setOnInsert("shopId", shopId)
        .setOnInsert("month", month)
        .setOnInsert("createdAt", now);
    boolean amount = request.getBillingAmount() != null && request.getBillingAmount().signum() > 0;
    update.inc("billingAmountUsed", amount ? new Decimal128(request.getBillingAmount()) : new Decimal128(BigDecimal.ZERO));
    incIfPositive(update, "billCountUsed", request.getBillCount());
    incIfPositive(update, "smsUsed", request.getSmsCount());
    incIfPositive(update, "whatsappUsed", request.getWhatsappCount());
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("month").is(month));
    Usage after = mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().upsert(true).returnNew(true), Usage.class);
    if (after != null && now.equals(after.getCreatedAt())) {
      purgeOldUsage(shopId); // this call created the month
    }
    return after;
  }

  private static void incIfPositive(Update update, String field, Integer delta) {
    update.inc(field, delta != null && delta > 0 ? delta : 0);
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
    if (shopInfo.planId() != null && !shopInfo.planId().isBlank()) {
      return planRepository.findById(shopInfo.planId())
          .orElseThrow(() -> new ResourceNotFoundException("Plan", "id", shopInfo.planId()));
    }
    return planRepository.findByPlanName("Base")
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "name", "Base"));
  }
}
