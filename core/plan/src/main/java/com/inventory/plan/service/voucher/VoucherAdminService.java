package com.inventory.plan.service.voucher;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.repository.AddOnRepository;
import com.inventory.plan.domain.repository.AddOnVoucherRepository;
import com.inventory.plan.domain.repository.VoucherRedemptionRepository;
import com.inventory.plan.mapper.VoucherMapper;
import com.inventory.plan.rest.dto.request.VoucherActiveRequest;
import com.inventory.plan.rest.dto.request.VoucherGenerateRequest;
import com.inventory.plan.rest.dto.request.VoucherUpdateRequest;
import com.inventory.plan.rest.dto.response.AdminVoucherResponse;
import com.inventory.plan.rest.dto.response.VoucherRedemptionResponse;
import com.inventory.plan.validation.VoucherAdminValidator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
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
  private static final int RANDOM_LENGTH = 6;
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
  private MongoTemplate mongoTemplate;

  @Autowired
  private VoucherMapper voucherMapper;

  @Autowired
  private VoucherAdminValidator validator;

  @Autowired
  private AuditService auditService;

  Clock clock = Clock.systemUTC();

  public List<AdminVoucherResponse> list(String addOnCode) {
    List<AddOnVoucher> vouchers = StringUtils.hasText(addOnCode)
        ? voucherRepository.findByAddOnCode(addOnCode.trim().toUpperCase(Locale.ROOT), NEWEST_FIRST)
        : voucherRepository.findAll(NEWEST_FIRST);
    return vouchers.stream().map(voucherMapper::toAdminResponse).toList();
  }

  public List<AdminVoucherResponse> generate(VoucherGenerateRequest request, String actorUserId) {
    validator.validateGenerate(request);
    String addOnCode = request.getAddOnCode().trim().toUpperCase(Locale.ROOT);
    AddOn addOn = addOnRepository.findByCode(addOnCode)
        .orElseThrow(() -> new ResourceNotFoundException("Add-on", "code", addOnCode));
    if (!addOn.isActive()) {
      throw new ValidationException("Add-on " + addOnCode + " is hidden; show it before issuing vouchers");
    }
    int count = request.getCount() != null ? request.getCount() : 1;
    String batchId = UUID.randomUUID().toString();
    String letters = addOnCode.replaceAll("[^A-Z0-9]", "");
    String prefix = StringUtils.hasText(request.getPrefix())
        ? request.getPrefix()
        : letters.substring(0, Math.min(3, letters.length()));
    Instant now = clock.instant();

    List<AddOnVoucher> created = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      AddOnVoucher voucher = voucherMapper.toEntity(request);
      voucher.setAddOnCode(addOnCode);
      voucher.setBatchId(batchId);
      voucher.setCreatedByUserId(actorUserId);
      voucher.setCreatedAt(now);
      voucher.setUpdatedAt(now);
      created.add(StringUtils.hasText(request.getCode())
          ? insertWithCode(voucher, request.getCode().trim().toUpperCase(Locale.ROOT))
          : insertWithGeneratedCode(voucher, prefix));
    }

    Map<String, Object> after = new LinkedHashMap<>();
    after.put("addOnCode", addOnCode);
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
    log.info("{} voucher(s) for {} created by {} (batch {})", count, addOnCode, actorUserId, batchId);
    return created.stream().map(voucherMapper::toAdminResponse).toList();
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
    Update update = new Update()
        .set("validTo", request.getValidTo())
        .set("maxRedemptions", request.getMaxRedemptions())
        .set("note", request.getNote())
        .set("updatedAt", clock.instant());
    AddOnVoucher before = mongoTemplate.findAndModify(new Query(criteria), update,
        FindAndModifyOptions.options().returnNew(false), AddOnVoucher.class);
    if (before == null) {
      throw new ValidationException("Voucher " + current.getCode() + " has already used more slots than "
          + request.getMaxRedemptions());
    }
    AddOnVoucher after = load(id);
    audit(actorUserId, "VOUCHER_UPDATED", id, snapshot(before), snapshot(after), request.getNote());
    return voucherMapper.toAdminResponse(after);
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
    return voucherMapper.toAdminResponse(after);
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
    throw new IllegalStateException("Could not generate a unique voucher code after " + MAX_CODE_ATTEMPTS + " attempts");
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
