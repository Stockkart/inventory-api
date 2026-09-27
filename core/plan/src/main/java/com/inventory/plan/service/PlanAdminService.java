package com.inventory.plan.service;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.mapper.PlanMapper;
import com.inventory.plan.rest.dto.request.PlanActiveRequest;
import com.inventory.plan.rest.dto.request.PlanAdminRequest;
import com.inventory.plan.rest.dto.response.AdminPlanResponse;
import com.inventory.plan.utils.constants.PlanCatalogueConstants;
import com.inventory.plan.validation.PlanAdminValidator;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Platform-admin create, edit and show/hide for catalogue plans. Plans are never deleted: shops and
 * orders keep pointing at them. Every change writes an audit entry and drops cached entitlements.
 */
@Service
@Slf4j
public class PlanAdminService {

  static final String TARGET_TYPE = "PLAN";

  private static final Comparator<Plan> ADMIN_ORDER =
      Comparator.comparing(Plan::getDisplayOrder, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(Plan::getPlanName, Comparator.nullsLast(Comparator.naturalOrder()));

  @Autowired
  private PlanRepository planRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private PlanMapper planMapper;

  @Autowired
  private PlanAdminValidator planAdminValidator;

  @Autowired
  private AuditService auditService;

  @Autowired
  private EntitlementService entitlementService;

  /** Every plan, hidden ones included, in pricing-page order. */
  public List<AdminPlanResponse> list() {
    return planRepository.findAll().stream()
        .sorted(ADMIN_ORDER)
        .map(planMapper::toAdminResponse)
        .toList();
  }

  public AdminPlanResponse create(PlanAdminRequest request, String actorUserId) {
    planAdminValidator.validateCreate(request);
    requireLinkedPlanExists(request.getLinkedId());
    if (planRepository.findByCode(request.getCode()).isPresent()) {
      throw new ValidationException("A plan with code " + request.getCode() + " already exists");
    }
    Plan plan = planMapper.toEntity(request);
    try {
      plan = mongoTemplate.insert(plan);
    } catch (DuplicateKeyException e) {
      throw new ValidationException("A plan with code " + request.getCode() + " already exists");
    }
    audit(actorUserId, "PLAN_CREATED", plan.getId(), null, snapshot(plan), null);
    log.info("Plan {} created by {}", plan.getCode(), actorUserId);
    return planMapper.toAdminResponse(plan);
  }

  /** Full edit. The code is fixed at create so catalogue keys stay stable. */
  public AdminPlanResponse update(String id, PlanAdminRequest request, String actorUserId) {
    planAdminValidator.validateUpdate(id, request);
    requireLinkedPlanExists(request.getLinkedId());
    Update update = new Update()
        .set("planName", request.getPlanName())
        .set("arcPrice", request.getArcPrice())
        .set("price", request.getPrice())
        .set("billingLimit", request.getBillingLimit())
        .set("billCountLimit", request.getBillCountLimit())
        .set("smsLimit", request.getSmsLimit())
        .set("whatsappLimit", request.getWhatsappLimit())
        .set("userLimit", request.getUserLimit())
        .set("ocrLimit", request.getOcrLimit())
        .set("unlimited", request.isUnlimited())
        .set("entitlements", request.getFeatures() == null ? List.of() : new TreeSet<>(request.getFeatures()))
        .set("displayOrder", request.getDisplayOrder())
        .set("badge", StringUtils.hasText(request.getBadge()) ? request.getBadge() : null)
        .set("bestFor", request.getBestFor())
        .set("linkedId", StringUtils.hasText(request.getLinkedId()) ? request.getLinkedId() : null);
    Plan before = mongoTemplate.findAndModify(byId(id), update,
        FindAndModifyOptions.options().returnNew(false), Plan.class);
    if (before == null) {
      throw new ResourceNotFoundException("Plan", "id", id);
    }
    Plan after = load(id);
    entitlementService.invalidateAll();
    audit(actorUserId, "PLAN_UPDATED", id, snapshot(before), snapshot(after), null);
    return planMapper.toAdminResponse(after);
  }

  /**
   * Hides or shows a plan on the pricing page. Hidden plans keep serving shops already on them.
   * Switching to the current value is a no-op and writes no audit entry.
   */
  public AdminPlanResponse setActive(String id, PlanActiveRequest request, String actorUserId) {
    planAdminValidator.validateActive(id, request);
    boolean active = request.getActive();
    Plan current = load(id);
    if (!active && PlanCatalogueConstants.TRIAL_PLAN_CODE.equals(current.getCode())) {
      throw new ValidationException("The trial plan cannot be hidden; new shops are measured against it");
    }
    // A null active flag already means "on", so only an explicit false needs switching on.
    Criteria needsChange = active
        ? Criteria.where("active").is(false)
        : Criteria.where("active").ne(false);
    Query query = new Query(Criteria.where("_id").is(id).andOperator(needsChange));
    Plan before = mongoTemplate.findAndModify(query, new Update().set("active", active),
        FindAndModifyOptions.options().returnNew(false), Plan.class);
    Plan after = load(id);
    if (before != null) {
      entitlementService.invalidateAll();
      audit(actorUserId, active ? "PLAN_ACTIVATED" : "PLAN_DEACTIVATED", id,
          snapshot(before), snapshot(after), request.getReason());
    }
    return planMapper.toAdminResponse(after);
  }

  private void requireLinkedPlanExists(String linkedId) {
    if (StringUtils.hasText(linkedId) && !planRepository.existsById(linkedId)) {
      throw new ValidationException("Upsell plan " + linkedId + " does not exist");
    }
  }

  private Plan load(String id) {
    return planRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "id", id));
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

  private static Query byId(String id) {
    return new Query(Criteria.where("_id").is(id));
  }

  private static Map<String, Object> snapshot(Plan plan) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("code", plan.getCode());
    map.put("planName", plan.getPlanName());
    map.put("arcPrice", plan.getArcPrice());
    map.put("price", plan.getPrice());
    map.put("billingLimit", plan.getBillingLimit());
    map.put("billCountLimit", plan.getBillCountLimit());
    map.put("smsLimit", plan.getSmsLimit());
    map.put("whatsappLimit", plan.getWhatsappLimit());
    map.put("userLimit", plan.getUserLimit());
    map.put("ocrLimit", plan.getOcrLimit());
    map.put("unlimited", plan.isUnlimited());
    map.put("features", plan.getFeatures() == null ? List.of()
        : plan.getFeatures().stream().map(Enum::name).sorted().toList());
    map.put("displayOrder", plan.getDisplayOrder());
    map.put("badge", plan.getBadge());
    map.put("bestFor", plan.getBestFor());
    map.put("linkedId", plan.getLinkedId());
    map.put("active", !Boolean.FALSE.equals(plan.getActive()));
    return map;
  }
}
