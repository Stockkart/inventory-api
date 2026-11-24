package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.audit.AuditUserType;
import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.rest.dto.request.AdminPlanGrantRequest;
import com.inventory.plan.rest.dto.response.ShopPlanStatusResponse;
import com.inventory.plan.validation.PlanAdminValidator;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShopPlanGrantServiceTest {

  @Spy private PlanAdminValidator planAdminValidator = new PlanAdminValidator();
  @Mock private PlanRepository planRepository;
  @Mock private PlanService planService;
  @Mock private AuditService auditService;
  @InjectMocks private ShopPlanGrantService service;

  @Test
  void aGrantIsAuditedWithTheAdminAsActorAndReturnsTheStatus() {
    Plan pro = new Plan();
    pro.setId("pro");
    pro.setCode("PROFESSIONAL");
    when(planRepository.findByCode("PROFESSIONAL")).thenReturn(Optional.of(pro));
    Instant end = Instant.parse("2027-01-01T00:00:00Z");
    when(planService.grantByAdmin("s1", pro, 2)).thenReturn(new PlanService.GrantResult(null, null, "pro", end));
    ShopPlanStatusResponse status = new ShopPlanStatusResponse();
    when(planService.getShopPlanStatus("s1")).thenReturn(status);

    ShopPlanStatusResponse response =
        service.grant("s1", new AdminPlanGrantRequest(" professional ", 2, " onboarding offer "), "admin-1");

    assertThat(response).isSameAs(status);
    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertThat(audit.getValue().getActorUserId()).isEqualTo("admin-1");
    assertThat(audit.getValue().getActorType()).isEqualTo(AuditUserType.ADMIN);
    assertThat(audit.getValue().getAction()).isEqualTo("PLAN_GRANTED");
    assertThat(audit.getValue().getTargetId()).isEqualTo("s1");
    assertThat(audit.getValue().getReason()).isEqualTo("onboarding offer");
    assertThat(audit.getValue().getAfter()).containsEntry("expiresAt", end.toString());
  }

  @Test
  void durationReasonAndPlanAreChecked() {
    assertThatThrownBy(() -> service.grant("s1", new AdminPlanGrantRequest("PRO", 0, "x"), "a"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.grant("s1", new AdminPlanGrantRequest("PRO", 37, "x"), "a"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.grant("s1", new AdminPlanGrantRequest("PRO", 1, " "), "a"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.grant("s1", new AdminPlanGrantRequest("PRO", 1, "x".repeat(201)), "a"))
        .isInstanceOf(ValidationException.class);

    Plan hidden = new Plan();
    hidden.setActive(false);
    when(planRepository.findByCode("OLD")).thenReturn(Optional.of(hidden));
    assertThatThrownBy(() -> service.grant("s1", new AdminPlanGrantRequest("old", 1, "x"), "a"))
        .isInstanceOf(ResourceNotFoundException.class);
    verify(planService, never()).grantByAdmin(any(), any(), anyInt());
  }
}
