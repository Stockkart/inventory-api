package com.inventory.plan.service;

import com.inventory.common.audit.AuditActorType;
import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.rest.dto.request.AdminPlanGrantRequest;
import com.inventory.plan.rest.dto.response.ShopPlanStatusResponse;
import com.inventory.plan.service.PlanService.GrantResult;
import com.inventory.plan.validation.PlanAdminValidator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Admin plan grants: a plan for a shop without payment, audited with the admin as actor. */
@Service
public class ShopPlanGrantService {

  static final String ACTION_GRANTED = "PLAN_GRANTED";

  @Autowired
  private PlanAdminValidator planAdminValidator;

  @Autowired
  private PlanRepository planRepository;

  @Autowired
  private PlanService planService;

  @Autowired
  private AuditService auditService;

  /** Grants an active catalogue plan and returns the shop's plan status after the grant. */
  public ShopPlanStatusResponse grant(String shopId, AdminPlanGrantRequest request, String actorAdminId) {
    planAdminValidator.validateGrant(shopId, request);
    String code = request.getPlanCode().trim().toUpperCase(Locale.ROOT);
    Plan plan = planRepository.findByCode(code)
        .filter(EffectivePlanResolver::isActive)
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "code", code));

    GrantResult result = planService.grantByAdmin(shopId, plan, request.getDurationMonths());

    Map<String, Object> before = new LinkedHashMap<>();
    before.put("planId", result.planIdBefore());
    before.put("expiresAt", result.expiresAtBefore() != null ? result.expiresAtBefore().toString() : null);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("planId", result.planIdAfter());
    after.put("planCode", code);
    after.put("durationMonths", request.getDurationMonths());
    after.put("expiresAt", result.expiresAtAfter().toString());
    auditService.record(AuditEntry.builder()
        .actorUserId(actorAdminId)
        .actorType(AuditActorType.ADMIN)
        .action(ACTION_GRANTED)
        .targetType("SHOP")
        .targetId(shopId)
        .before(before)
        .after(after)
        .reason(request.getReason().trim())
        .source(AuditSource.ADMIN_UI)
        .build());
    return planService.getShopPlanStatus(shopId);
  }
}
