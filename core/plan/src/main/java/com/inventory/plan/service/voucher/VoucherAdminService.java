package com.inventory.plan.service.voucher;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.VoucherTarget;
import com.inventory.plan.domain.repository.AddOnRepository;
import com.inventory.plan.domain.repository.AddOnVoucherRepository;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.domain.repository.VoucherRedemptionRepository;
import com.inventory.plan.mapper.VoucherMapper;
import com.inventory.plan.rest.dto.request.PlanVoucherPreviewRequest;
import com.inventory.plan.rest.dto.request.VoucherActiveRequest;
import com.inventory.plan.rest.dto.request.VoucherGenerateRequest;
import com.inventory.plan.rest.dto.request.VoucherUpdateRequest;
import com.inventory.plan.rest.dto.response.AdminVoucherResponse;
import com.inventory.plan.rest.dto.response.PlanVoucherPreviewResponse;
import com.inventory.plan.rest.dto.response.VoucherRedemptionResponse;
import com.inventory.plan.service.EffectivePlanResolver;
import com.inventory.plan.utils.PlanVoucherCheck;
import com.inventory.plan.utils.PlanVoucherPricing;
import com.inventory.plan.validation.VoucherAdminValidator;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.ComparisonOperators;
import org.springframework.data.mongodb.core.aggregation.ArithmeticOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Platform-admin voucher management (5c). Every change is audited. */
@Service
@Slf4j
public class VoucherAdminService {

  static final String TARGET_TYPE = "ADD_ON_VOUCHER";
  /** No 0/O or 1/I, so codes survive being read out over the phone. */
  private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
  /** 31^10 codes per prefix, so guessing one is impractical even before the lookup limiter. */
  static final int RANDOM_LENGTH = 10;
  private static final int MAX_CODE_ATTEMPTS = 5;
  private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

  private final SecureRandom random = new SecureRandom();

  @Autowired
  private AddOnVoucherRepository voucherRepository;

  @Autowired
  private VoucherRedemptionRepository redemptionRepository;

  @Autowired
  private AddOnRepository addOnRepository;

  @Autowired
  private PlanRepository planRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private VoucherMapper voucherMapper;

  @Autowired
  private VoucherAdminValidator validator;

  @Autowired
  private AuditService auditService;

  Clock clock = Clock.systemUTC();

  public static final int DEFAULT_PAGE_SIZE = 100;

  /**
   * Newest first, one page at a time (page from 0; size 1-500, default 100). At most one filter
   * applies, in the order planCode, addOnCode, target, none.
   */
  public List<AdminVoucherResponse> list(String addOnCode, String planCode, VoucherTarget target,
      Integer page, Integer size) {
    validator.validatePage(page, size);
    Pageable pageable = PageRequest.of(page != null ? page : 0, size != null ? size : DEFAULT_PAGE_SIZE, NEWEST_FIRST);
    List<AddOnVoucher> vouchers;
    if (StringUtils.hasText(planCode)) {
      vouchers = voucherRepository.findByPlanCode(planCode.trim().toUpperCase(Locale.ROOT), pageable);
    } else if (StringUtils.hasText(addOnCode)) {
      vouchers = voucherRepository.findByAddOnCode(addOnCode.trim().toUpperCase(Locale.ROOT), pageable);
    } else if (target == VoucherTarget.PLAN) {
      vouchers = voucherRepository.findByTarget(VoucherTarget.PLAN, pageable);
    } else if (target == VoucherTarget.ADD_ON) {
      // Legacy vouchers have no target stored; they are add-on vouchers too.
      vouchers = voucherRepository.findAddOnTargeted(pageable);
    } else {
      vouchers = voucherRepository.findAll(pageable).getContent();
    }
    Map<String, String> planNames = planNamesByCode(vouchers.stream()
        .filter(AddOnVoucher::isPlanVoucher).map(AddOnVoucher::getPlanCode).collect(Collectors.toSet()));
    return vouchers.stream().map(v -> toResponse(v, v.getPlanCode() == null ? null : planNames.get(v.getPlanCode()))).toList();
  }

  public List<AdminVoucherResponse> generate(VoucherGenerateRequest request, String actorUserId) {
    validator.validateGenerate(request);
    boolean forPlan = request.getTarget() == VoucherTarget.PLAN;
    String targetCode;
    String planName = null;
    if (forPlan) {
      targetCode = request.getPlanCode().trim().toUpperCase(Locale.ROOT);
      Plan plan = requireSellablePlan(targetCode);
      PlanVoucherCheck check = PlanVoucherPricing.check(request.getType(), request.getValue(), plan);
      if (!check.allowed()) {
        throw new ValidationException(PlanVoucherPricing.belowFloorMessage(plan, check));
      }
      planName = plan.getPlanName();
    } else {
      targetCode = request.getAddOnCode().trim().toUpperCase(Locale.ROOT);
      AddOn addOn = addOnRepository.findByCode(targetCode)
          .orElseThrow(() -> new ResourceNotFoundException("Add-on", "code", targetCode));
      if (!addOn.isActive()) {
        throw new ValidationException("Add-on " + targetCode + " is hidden; show it before issuing vouchers");
      }
    }
    int count = request.getCount() != null ? request.getCount() : 1;
    String batchId = UUID.randomUUID().toString();
    String letters = targetCode.replaceAll("[^A-Z0-9]", "");
    String prefix = StringUtils.hasText(request.getPrefix())
        ? request.getPrefix()
        : letters.substring(0, Math.min(3, letters.length()));
    Instant now = clock.instant();

    // All or none: if any insert fails, the batch's earlier vouchers are removed again. Not a MongoTx:
    // a generated code that collides is retried, and a failed write would abort the transaction.
    List<AddOnVoucher> created = new ArrayList<>();
    try {
      for (int i = 0; i < count; i++) {
        AddOnVoucher voucher = voucherMapper.toEntity(request);
        if (forPlan) {
          voucher.setTarget(VoucherTarget.PLAN);
          voucher.setPlanCode(targetCode);
          voucher.setAddOnCode(null);
        } else {
          voucher.setAddOnCode(targetCode);
        }
        voucher.setBatchId(batchId);
        voucher.setCreatedByUserId(actorUserId);
        voucher.setCreatedAt(now);
        voucher.setUpdatedAt(now);
        created.add(StringUtils.hasText(request.getCode())
            ? insertWithCode(voucher, request.getCode().trim().toUpperCase(Locale.ROOT))
            : insertWithGeneratedCode(voucher, prefix));
      }
    } catch (RuntimeException e) {
      if (!created.isEmpty()) {
        mongoTemplate.remove(new Query(Criteria.where("batchId").is(batchId)), AddOnVoucher.class);
        log.warn("Voucher batch {} for {} stopped after {} of {}; removed them again", batchId, targetCode,
            created.size(), count);
      }
      throw e;
    }

    Map<String, Object> after = new LinkedHashMap<>();
    if (forPlan) {
      after.put("target", VoucherTarget.PLAN);
      after.put("planCode", targetCode);
    } else {
      after.put("addOnCode", targetCode);
    }
    after.put("type", request.getType());
    after.put("value", request.getValue());
    after.put("count", count);
    after.put("maxRedemptions", request.getMaxRedemptions());
    after.put("singleUsePerShop", request.isSingleUsePerShop());
    after.put("issuedToShopId", request.getIssuedToShopId());
    after.put("validFrom", request.getValidFrom());
    after.put("validTo", request.getValidTo());
    after.put("codes", created.stream().map(AddOnVoucher::getCode).toList());
    audit(actorUserId, "VOUCHER_CREATED", count == 1 ? created.get(0).getId() : batchId, null, after, request.getNote());
    log.info("{} voucher(s) for {}{} created by {} (batch {})", count, forPlan ? "plan " : "", targetCode,
        actorUserId, batchId);
    String name = planName;
    return created.stream().map(v -> toResponse(v, name)).toList();
  }

  /** Read-only: the figures and verdict the admin form shows while typing. Fails like generate for a bad plan. */
  public PlanVoucherPreviewResponse previewPlan(PlanVoucherPreviewRequest request) {
    validator.validatePlanPreview(request);
    String planCode = request.getPlanCode().trim().toUpperCase(Locale.ROOT);
    Plan plan = requireSellablePlan(planCode);
    PlanVoucherCheck check = PlanVoucherPricing.check(request.getType(), request.getValue(), plan);
    return PlanVoucherPreviewResponse.builder()
        .planCode(planCode)
        .planName(plan.getPlanName())
        .price(check.price())
        .discount(check.discount())
        .priceAfter(check.priceAfter())
        .basePrice(check.basePrice())
        .floor(check.floor())
        .maxFlat(check.maxFlat())
        .maxPercent(check.maxPercent())
        .allowed(check.allowed())
        .message(check.allowed() ? null : PlanVoucherPricing.belowFloorMessage(plan, check))
        .build();
  }

  private Plan requireSellablePlan(String planCode) {
    Plan plan = planRepository.findByCode(planCode)
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "code", planCode));
    if (!EffectivePlanResolver.isActive(plan)) {
      throw new ValidationException("Plan " + planCode + " is hidden; show it before issuing vouchers");
    }
    BigDecimal price = PlanVoucherPricing.planPrice(plan);
    if (price == null || price.signum() <= 0) {
      throw new ValidationException("Plan " + planCode + " has no sale price, so it cannot take a voucher");
    }
    return plan;
  }

  /** Plan names by code, loaded once per list call. Legacy plans without a code are skipped. */
  /** Names of just the plans this page of vouchers names. */
  private Map<String, String> planNamesByCode(java.util.Set<String> codes) {
    if (codes.isEmpty()) {
      return Map.of();
    }
    return planRepository.findByCodeIn(codes).stream()
        .filter(plan -> plan.getCode() != null)
        .collect(Collectors.toMap(Plan::getCode, Plan::getPlanName, (a, b) -> a));
  }

  private String planNameOf(AddOnVoucher voucher) {
    return voucher.isPlanVoucher()
        ? planRepository.findByCode(voucher.getPlanCode()).map(Plan::getPlanName).orElse(null)
        : null;
  }

  private AdminVoucherResponse toResponse(AddOnVoucher voucher, String planName) {
    AdminVoucherResponse response = voucherMapper.toAdminResponse(voucher);
    response.setPlanName(planName);
    return response;
  }

  /** Changes validity, cap or note. The cap cannot drop below the slots already taken. */
  public AdminVoucherResponse update(String id, VoucherUpdateRequest request, String actorUserId) {
    validator.validateUpdate(id, request);
    AddOnVoucher current = load(id);
    if (request.getValidTo() != null && current.getValidFrom() != null && !request.getValidTo().isAfter(current.getValidFrom())) {
      throw new ValidationException("Valid-to must be after valid-from");
    }
    Criteria criteria = Criteria.where("_id").is(id);
    if (request.getMaxRedemptions() != null) {
      criteria = criteria.andOperator(Criteria.expr(ComparisonOperators.Lte.valueOf(
          ArithmeticOperators.Add.valueOf("reservedCount").add("redemptionCount")).lessThanEqualToValue(request.getMaxRedemptions())));
    }
    // only the fields the request sends; an omitted field keeps its value
    Update update = new Update().set("updatedAt", clock.instant());
    if (request.getValidTo() != null) {
      update.set("validTo", request.getValidTo());
    }
    if (request.getMaxRedemptions() != null) {
      update.set("maxRedemptions", request.getMaxRedemptions());
    }
    if (request.getNote() != null) {
      update.set("note", request.getNote());
    }
    AddOnVoucher before = mongoTemplate.findAndModify(new Query(criteria), update,
        FindAndModifyOptions.options().returnNew(false), AddOnVoucher.class);
    if (before == null) {
      throw new ValidationException("Voucher " + current.getCode() + " has already used more slots than "
          + request.getMaxRedemptions());
    }
    AddOnVoucher after = load(id);
    audit(actorUserId, "VOUCHER_UPDATED", id, snapshot(before), snapshot(after), request.getNote());
    return toResponse(after, planNameOf(after));
  }

  /** Disabling stops new checkouts; orders already holding a slot keep it. */
  public AdminVoucherResponse setActive(String id, VoucherActiveRequest request, String actorUserId) {
    validator.validateActive(id, request);
    boolean active = request.getActive();
    Criteria needsChange = active ? Criteria.where("active").is(false) : Criteria.where("active").ne(false);
    AddOnVoucher before = mongoTemplate.findAndModify(
        new Query(Criteria.where("_id").is(id).andOperator(needsChange)),
        new Update().set("active", active).set("updatedAt", clock.instant()),
        FindAndModifyOptions.options().returnNew(false), AddOnVoucher.class);
    AddOnVoucher after = load(id);
    if (before != null) {
      audit(actorUserId, active ? "VOUCHER_ACTIVATED" : "VOUCHER_DEACTIVATED", id,
          snapshot(before), snapshot(after), request.getReason());
    }
    return toResponse(after, planNameOf(after));
  }

  public List<VoucherRedemptionResponse> redemptions(String id) {
    load(id);
    return redemptionRepository.findByVoucherId(id, Sort.by(Sort.Direction.DESC, "reservedAt")).stream()
        .map(voucherMapper::toRedemptionResponse)
        .toList();
  }

  private AddOnVoucher insertWithCode(AddOnVoucher voucher, String code) {
    voucher.setCode(code);
    try {
      return mongoTemplate.insert(voucher);
    } catch (DuplicateKeyException e) {
      throw new ValidationException("Voucher code " + code + " already exists");
    }
  }

  private AddOnVoucher insertWithGeneratedCode(AddOnVoucher voucher, String prefix) {
    for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
      voucher.setId(null);
      voucher.setCode(prefix + "-" + randomPart());
      try {
        return mongoTemplate.insert(voucher);
      } catch (DuplicateKeyException e) {
        log.debug("Generated voucher code {} already exists; retrying", voucher.getCode());
      }
    }
    throw new BaseException(ErrorCode.DUPLICATE_RESOURCE,
        "Could not generate a unique voucher code after " + MAX_CODE_ATTEMPTS + " attempts; try again");
  }

  private String randomPart() {
    char[] chars = new char[RANDOM_LENGTH];
    for (int i = 0; i < RANDOM_LENGTH; i++) {
      chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
    }
    return new String(chars);
  }

  private AddOnVoucher load(String id) {
    return voucherRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Voucher", "id", id));
  }

  private void audit(String actorUserId, String action, String targetId,
      Map<String, Object> before, Map<String, Object> after, String reason) {
    auditService.record(AuditEntry.builder()
        .actorUserId(actorUserId)
        .action(action)
        .targetType(TARGET_TYPE)
        .targetId(targetId)
        .before(before)
        .after(after)
        .reason(reason)
        .source(AuditSource.ADMIN_UI)
        .build());
  }

  private static Map<String, Object> snapshot(AddOnVoucher voucher) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("code", voucher.getCode());
    map.put("validTo", voucher.getValidTo());
    map.put("maxRedemptions", voucher.getMaxRedemptions());
    map.put("note", voucher.getNote());
    map.put("active", voucher.isActive());
    return map;
  }
}
