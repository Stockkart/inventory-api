package com.inventory.plan.service;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.domain.model.ShopAddOn;
import com.inventory.plan.domain.repository.AddOnRepository;
import com.inventory.plan.mapper.AddOnMapper;
import com.inventory.plan.rest.dto.request.AddOnActiveRequest;
import com.inventory.plan.rest.dto.request.AddOnAdminRequest;
import com.inventory.plan.rest.dto.request.AddOnGrantRequest;
import com.inventory.plan.rest.dto.response.AdminAddOnResponse;
import com.inventory.plan.rest.dto.response.ShopAddOnResponse;
import com.inventory.plan.validation.AddOnAdminValidator;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Platform-admin catalogue management for add-ons, plus manual grants. Add-ons are never deleted,
 * since shop grants and orders refer to them. Every change is audited.
 */
@Service
@Slf4j
public class AddOnAdminService {

  static final String TARGET_TYPE = "ADD_ON";
  static final String GRANT_TARGET_TYPE = "SHOP_ADD_ON";

  @Autowired
  private AddOnRepository addOnRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private AddOnMapper addOnMapper;

  @Autowired
  private AddOnAdminValidator validator;

  @Autowired
  private AuditService auditService;

  @Autowired
  private EntitlementService entitlementService;

  @Autowired
  private ShopAddOnService shopAddOnService;

  Clock clock = Clock.systemUTC();

  public List<AdminAddOnResponse> list() {
    return addOnRepository.findAll().stream()
        .sorted(AddOnCatalogueService.DISPLAY_ORDER)
        .map(addOnMapper::toAdminResponse)
        .toList();
  }

  public AdminAddOnResponse create(AddOnAdminRequest request, String actorUserId) {
    validator.validateCreate(request);
    AddOn addOn = addOnMapper.toEntity(request);
    if (addOn.getGrantType() == AddOnGrantType.FEATURE) {
      addOn.setGrantsQuantity(1);
    }
    Instant now = clock.instant();
    addOn.setCreatedAt(now);
    addOn.setUpdatedAt(now);
    try {
      addOn = mongoTemplate.insert(addOn);
    } catch (DuplicateKeyException e) {
      throw new ValidationException("An add-on with code " + request.getCode() + " already exists");
    }
    audit(actorUserId, "ADD_ON_CREATED", TARGET_TYPE, addOn.getId(), null, snapshot(addOn), null);
    return addOnMapper.toAdminResponse(addOn);
  }

  /** Full edit. Code and grant type are fixed at create. */
  public AdminAddOnResponse update(String id, AddOnAdminRequest request, String actorUserId) {
    AddOn current = load(id);
    validator.validateUpdate(id, request, current.getGrantType());
    Update update = new Update()
        .set("name", request.getName())
        .set("description", request.getDescription())
        .set("price", request.getPrice())
        .set("billingType", request.getBillingType())
        .set("grantsFeature", request.getGrantsFeature())
        .set("grantsQuantity", current.getGrantType() == AddOnGrantType.FEATURE ? 1 : request.getGrantsQuantity())
        .set("stackable", request.isStackable())
        .set("maxQuantity", request.getMaxQuantity())
        .set("displayOrder", request.getDisplayOrder())
        .set("updatedAt", clock.instant());
    AddOn before = mongoTemplate.findAndModify(byId(id), update,
        FindAndModifyOptions.options().returnNew(false), AddOn.class);
    if (before == null) {
      throw new ResourceNotFoundException("Add-on", "id", id);
    }
    AddOn after = load(id);
    audit(actorUserId, "ADD_ON_UPDATED", TARGET_TYPE, id, snapshot(before), snapshot(after), null);
    return addOnMapper.toAdminResponse(after);
  }

  /** Hides or shows an add-on at checkout. Existing grants are untouched. */
  public AdminAddOnResponse setActive(String id, AddOnActiveRequest request, String actorUserId) {
    validator.validateActive(id, request);
    boolean active = request.getActive();
    Criteria needsChange = active
        ? Criteria.where("active").is(false)
        : Criteria.where("active").ne(false);
    Query query = new Query(Criteria.where("_id").is(id).andOperator(needsChange));
    AddOn before = mongoTemplate.findAndModify(query,
        new Update().set("active", active).set("updatedAt", clock.instant()),
        FindAndModifyOptions.options().returnNew(false), AddOn.class);
    AddOn after = load(id);
    if (before != null) {
      audit(actorUserId, active ? "ADD_ON_ACTIVATED" : "ADD_ON_DEACTIVATED", TARGET_TYPE, id,
          snapshot(before), snapshot(after), request.getReason());
    }
    return addOnMapper.toAdminResponse(after);
  }

  /**
   * Grants an add-on to a shop without an order. An annual add-on ends at {@code expiresAt}, or with
   * the shop's current term (so a trial grant ends with the trial, r4.15).
   */
  public ShopAddOnResponse grant(AddOnGrantRequest request, String actorUserId) {
    validator.validateGrant(request);
    String code = request.getAddOnCode().trim().toUpperCase(Locale.ROOT);
    AddOn addOn = addOnRepository.findByCode(code)
        .orElseThrow(() -> new ResourceNotFoundException("Add-on", "code", code));
    int quantity = request.getQuantity() != null ? request.getQuantity() : 1;
    if (addOn.getGrantType() == AddOnGrantType.FEATURE && quantity != 1) {
      throw new ValidationException("A feature add-on is granted once");
    }
    Instant now = clock.instant();
    Instant expiresAt = null;
    if (addOn.getGrantType() != AddOnGrantType.OCR_CREDITS) {
      expiresAt = request.getExpiresAt() != null
          ? request.getExpiresAt()
          : entitlementService.resolve(request.getShopId()).expiresAt();
      if (expiresAt == null || !expiresAt.isAfter(now)) {
        throw new ValidationException("The shop has no current term to attach this add-on to; give a future expiresAt");
      }
    }
    ShopAddOn granted = shopAddOnService.grantByAdmin(request.getShopId(), addOn, quantity, expiresAt,
        actorUserId, request.getReason());
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("shopId", granted.getShopId());
    after.put("addOnCode", granted.getAddOnCode());
    after.put("quantity", granted.getQuantity());
    after.put("grantedQuantity", granted.getGrantedQuantity());
    after.put("expiresAt", granted.getExpiresAt());
    audit(actorUserId, "ADD_ON_GRANTED", GRANT_TARGET_TYPE, granted.getId(), null, after, request.getReason());
    log.info("Add-on {} x{} granted to shop {} by {}", code, quantity, request.getShopId(), actorUserId);
    return addOnMapper.toShopAddOnResponse(granted, now);
  }

  /** Every add-on a shop has held, for support. */
  public List<ShopAddOnResponse> listForShop(String shopId) {
    return shopAddOnService.describe(shopId);
  }

  private AddOn load(String id) {
    return addOnRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Add-on", "id", id));
  }

  private void audit(String actorUserId, String action, String targetType, String targetId,
      Map<String, Object> before, Map<String, Object> after, String reason) {
    auditService.record(AuditEntry.builder()
        .actorUserId(actorUserId)
        .action(action)
        .targetType(targetType)
        .targetId(targetId)
        .before(before)
        .after(after)
        .reason(reason)
        .source(AuditSource.ADMIN_UI)
        .build());
  }

  private static Query byId(String id) {
    return new Query(Criteria.where("_id").is(id));
  }

  private static Map<String, Object> snapshot(AddOn addOn) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("code", addOn.getCode());
    map.put("name", addOn.getName());
    map.put("price", addOn.getPrice());
    map.put("billingType", addOn.getBillingType());
    map.put("grantType", addOn.getGrantType());
    map.put("grantsFeature", addOn.getGrantsFeature());
    map.put("grantsQuantity", addOn.getGrantsQuantity());
    map.put("stackable", addOn.isStackable());
    map.put("maxQuantity", addOn.getMaxQuantity());
    map.put("displayOrder", addOn.getDisplayOrder());
    map.put("active", addOn.isActive());
    return map;
  }
}
