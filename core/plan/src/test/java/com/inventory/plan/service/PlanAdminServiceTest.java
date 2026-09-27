package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.entitlement.PlanFeature;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.mapper.PlanMapper;
import com.inventory.plan.mapper.PlanMapperImpl;
import com.inventory.plan.rest.dto.request.PlanActiveRequest;
import com.inventory.plan.rest.dto.request.PlanAdminRequest;
import com.inventory.plan.rest.dto.response.AdminPlanResponse;
import com.inventory.plan.validation.PlanAdminValidator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class PlanAdminServiceTest {

  @Mock
  private PlanRepository planRepository;
  @Mock
  private MongoTemplate mongoTemplate;
  @Mock
  private AuditService auditService;
  @Mock
  private EntitlementService entitlementService;
  @Spy
  private PlanMapper planMapper = new PlanMapperImpl();
  @Spy
  private PlanAdminValidator planAdminValidator = new PlanAdminValidator();

  @InjectMocks
  private PlanAdminService service;

  private static PlanAdminRequest request() {
    PlanAdminRequest request = new PlanAdminRequest();
    request.setCode("GROWTH");
    request.setPlanName("Growth");
    request.setArcPrice(new BigDecimal("7999"));
    request.setUserLimit(3);
    request.setFeatures(Set.of(PlanFeature.ACCOUNTING));
    request.setDisplayOrder(2);
    return request;
  }

  private static Plan stored(String code, Boolean active, BigDecimal arcPrice) {
    Plan plan = new Plan();
    plan.setId("p1");
    plan.setCode(code);
    plan.setPlanName("Growth");
    plan.setArcPrice(arcPrice);
    plan.setActive(active);
    return plan;
  }

  @Test
  void createInsertsActivePlanAndAudits() {
    when(planRepository.findByCode("GROWTH")).thenReturn(Optional.empty());
    when(mongoTemplate.insert(any(Plan.class))).thenAnswer(inv -> {
      Plan plan = inv.getArgument(0);
      plan.setId("p1");
      return plan;
    });

    AdminPlanResponse response = service.create(request(), "admin-1");

    assertThat(response.isActive()).isTrue();
    assertThat(response.getListPrice()).isEqualByComparingTo("10999");
    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertThat(audit.getValue().getAction()).isEqualTo("PLAN_CREATED");
    assertThat(audit.getValue().getTargetType()).isEqualTo("PLAN");
    assertThat(audit.getValue().getAfter()).containsEntry("features", List.of("ACCOUNTING"));
  }

  @Test
  void createRejectsDuplicateCodeFromLookupOrIndexRace() {
    when(planRepository.findByCode("GROWTH")).thenReturn(Optional.of(stored("GROWTH", true, null)));
    assertThatThrownBy(() -> service.create(request(), "admin-1")).isInstanceOf(ValidationException.class);

    when(planRepository.findByCode("GROWTH")).thenReturn(Optional.empty());
    when(mongoTemplate.insert(any(Plan.class))).thenThrow(new DuplicateKeyException("dup"));
    assertThatThrownBy(() -> service.create(request(), "admin-1")).isInstanceOf(ValidationException.class);
    verify(auditService, never()).record(any());
  }

  @Test
  void createRejectsUnknownUpsellPlan() {
    PlanAdminRequest request = request();
    request.setLinkedId("missing");
    when(planRepository.existsById("missing")).thenReturn(false);

    assertThatThrownBy(() -> service.create(request, "admin-1")).isInstanceOf(ValidationException.class);
  }

  @Test
  void updateAuditsReplacedPriceAndDropsEntitlementCache() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Plan.class)))
        .thenReturn(stored("GROWTH", true, new BigDecimal("6999")));
    when(planRepository.findById("p1")).thenReturn(Optional.of(stored("GROWTH", true, new BigDecimal("7999"))));

    service.update("p1", request(), "admin-1");

    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).findAndModify(any(Query.class), update.capture(), any(FindAndModifyOptions.class), eq(Plan.class));
    Document set = (Document) update.getValue().getUpdateObject().get("$set");
    assertThat(set).doesNotContainKey("code");
    assertThat(set).containsKey("entitlements");
    verify(entitlementService).invalidateAll();
    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertThat(audit.getValue().getAction()).isEqualTo("PLAN_UPDATED");
    assertThat(audit.getValue().getBefore()).containsEntry("arcPrice", new BigDecimal("6999"));
    assertThat(audit.getValue().getAfter()).containsEntry("arcPrice", new BigDecimal("7999"));
  }

  @Test
  void updateMissingPlanIs404WithoutAudit() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Plan.class)))
        .thenReturn(null);

    assertThatThrownBy(() -> service.update("p1", request(), "admin-1"))
        .isInstanceOf(ResourceNotFoundException.class);
    verify(auditService, never()).record(any());
  }

  @Test
  void deactivateRecordsReason() {
    when(planRepository.findById("p1")).thenReturn(Optional.of(stored("GROWTH", true, null)),
        Optional.of(stored("GROWTH", false, null)));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Plan.class)))
        .thenReturn(stored("GROWTH", true, null));

    AdminPlanResponse response = service.setActive("p1", new PlanActiveRequest(false, "retired"), "admin-1");

    assertThat(response.isActive()).isFalse();
    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertThat(audit.getValue().getAction()).isEqualTo("PLAN_DEACTIVATED");
    assertThat(audit.getValue().getReason()).isEqualTo("retired");
    verify(entitlementService).invalidateAll();
  }

  @Test
  void noOpToggleWritesNoAudit() {
    when(planRepository.findById("p1")).thenReturn(Optional.of(stored("GROWTH", null, null)));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Plan.class)))
        .thenReturn(null);

    service.setActive("p1", new PlanActiveRequest(true, null), "admin-1");

    verify(auditService, never()).record(any());
    verify(entitlementService, never()).invalidateAll();
  }

  @Test
  void trialPlanCannotBeHidden() {
    when(planRepository.findById("p1")).thenReturn(Optional.of(stored("STARTER", true, null)));

    assertThatThrownBy(() -> service.setActive("p1", new PlanActiveRequest(false, "x"), "admin-1"))
        .isInstanceOf(ValidationException.class);
  }
}
