package com.inventory.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.AdminUser;
import com.inventory.user.domain.repository.AdminSessionRepository;
import com.inventory.user.domain.repository.AdminUserRepository;
import com.inventory.user.domain.repository.AdminUserWriter;
import com.inventory.user.rest.dto.request.AdminCreateRequest;
import com.inventory.user.rest.dto.request.AdminUserActiveRequest;
import com.inventory.user.rest.dto.request.AdminUserReasonRequest;
import com.inventory.user.rest.dto.response.AdminPasswordIssuedResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-28T06:00:00Z");

  @Mock
  private AdminUserRepository adminUserRepository;

  @Mock
  private AdminSessionRepository sessionRepository;

  @Mock
  private AdminUserWriter adminUserWriter;

  @Mock
  private AuditService auditService;

  @Spy
  private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

  @InjectMocks
  private AdminUserService service;

  @BeforeEach
  void fixClock() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  void createIssuesAOneTimeTemporaryPasswordAndAuditsWithoutIt() {
    when(adminUserRepository.save(any(AdminUser.class))).thenAnswer(i -> {
      AdminUser admin = i.getArgument(0);
      admin.setId("a2");
      return admin;
    });

    AdminPasswordIssuedResponse response =
        service.create(new AdminCreateRequest(" New@StockKart.in ", " New Admin "), "a1");

    ArgumentCaptor<AdminUser> saved = ArgumentCaptor.forClass(AdminUser.class);
    verify(adminUserRepository).save(saved.capture());
    assertThat(saved.getValue().getEmail()).isEqualTo("new@stockkart.in");
    assertThat(saved.getValue().getName()).isEqualTo("New Admin");
    assertThat(saved.getValue().isMustChangePassword()).isTrue();
    assertThat(saved.getValue().getCreatedByAdminId()).isEqualTo("a1");
    assertThat(response.getTemporaryPassword()).hasSize(16);
    assertThat(passwordEncoder.matches(response.getTemporaryPassword(), saved.getValue().getPasswordHash())).isTrue();

    AuditEntry audit = captureAudit();
    assertThat(audit.getAction()).isEqualTo(AdminUserService.ACTION_CREATED);
    assertThat(audit.getActorUserId()).isEqualTo("a1");
    assertThat(audit.getSource()).isEqualTo(AuditSource.ADMIN_UI);
    assertThat(audit.toString()).doesNotContain(response.getTemporaryPassword())
        .doesNotContain(saved.getValue().getPasswordHash());
  }

  @Test
  void createRejectsDuplicateAndInvalidInput() {
    when(adminUserRepository.existsByEmail("ops@stockkart.in")).thenReturn(true);
    assertThatThrownBy(() -> service.create(new AdminCreateRequest("ops@stockkart.in", "Ops"), "a1"))
        .isInstanceOf(ValidationException.class).hasMessageContaining("already exists");

    when(adminUserRepository.save(any(AdminUser.class))).thenThrow(new DuplicateKeyException("race"));
    assertThatThrownBy(() -> service.create(new AdminCreateRequest("race@stockkart.in", "Race"), "a1"))
        .isInstanceOf(ValidationException.class).hasMessageContaining("already exists");

    assertThatThrownBy(() -> service.create(new AdminCreateRequest("not-an-email", "X"), "a1"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.create(new AdminCreateRequest("x@y.io", " "), "a1"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.create(new AdminCreateRequest("x@y.io", "n".repeat(61)), "a1"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void disablingSignsTheAdminOutAndAuditsTheReason() {
    when(adminUserRepository.findById("a2")).thenReturn(Optional.of(admin("a2", true)));
    when(adminUserRepository.countByActiveTrue()).thenReturn(2L);

    var response = service.setActive("a2", new AdminUserActiveRequest(false, " left the team "), "a1");

    assertThat(response.isActive()).isFalse();
    verify(adminUserWriter).setActive("a2", false, NOW);
    verify(sessionRepository).deleteByAdminId("a2");
    AuditEntry audit = captureAudit();
    assertThat(audit.getAction()).isEqualTo(AdminUserService.ACTION_DISABLED);
    assertThat(audit.getReason()).isEqualTo("left the team");
  }

  @Test
  void cannotDisableYourselfOrTheLastActiveAdmin() {
    when(adminUserRepository.findById("a1")).thenReturn(Optional.of(admin("a1", true)));
    assertThatThrownBy(() -> service.setActive("a1", new AdminUserActiveRequest(false, null), "a1"))
        .isInstanceOf(ValidationException.class).hasMessageContaining("your own");

    when(adminUserRepository.findById("a2")).thenReturn(Optional.of(admin("a2", true)));
    when(adminUserRepository.countByActiveTrue()).thenReturn(1L);
    assertThatThrownBy(() -> service.setActive("a2", new AdminUserActiveRequest(false, null), "a1"))
        .isInstanceOf(ValidationException.class).hasMessageContaining("At least one");

    verify(adminUserWriter, never()).setActive(anyString(), anyBoolean(), any());
  }

  @Test
  void enablingDoesNotTouchSessionsAndNoOpChangesAreNotAudited() {
    when(adminUserRepository.findById("a2")).thenReturn(Optional.of(admin("a2", false)));
    service.setActive("a2", new AdminUserActiveRequest(true, null), "a1");
    verify(adminUserWriter).setActive("a2", true, NOW);
    verify(sessionRepository, never()).deleteByAdminId(anyString());

    when(adminUserRepository.findById("a3")).thenReturn(Optional.of(admin("a3", true)));
    service.setActive("a3", new AdminUserActiveRequest(true, null), "a1");
    verify(adminUserWriter, never()).setActive(eq("a3"), anyBoolean(), any());
  }

  @Test
  void setActiveRequiresTheFlag() {
    assertThatThrownBy(() -> service.setActive("a2", new AdminUserActiveRequest(null, null), "a1"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void resetIssuesATemporaryPasswordClearsLockoutAndSignsOut() {
    when(adminUserRepository.findById("a2")).thenReturn(Optional.of(admin("a2", true)));

    AdminPasswordIssuedResponse response = service.resetPassword("a2", new AdminUserReasonRequest("forgot"), "a1");

    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(adminUserWriter).setPassword(eq("a2"), hash.capture(), eq(true), eq(NOW));
    assertThat(passwordEncoder.matches(response.getTemporaryPassword(), hash.getValue())).isTrue();
    assertThat(response.getAdmin().isMustChangePassword()).isTrue();
    verify(sessionRepository).deleteByAdminId("a2");
    AuditEntry audit = captureAudit();
    assertThat(audit.getAction()).isEqualTo(AdminUserService.ACTION_PASSWORD_RESET);
    assertThat(audit.toString()).doesNotContain(response.getTemporaryPassword());
  }

  @Test
  void cannotResetYourOwnPassword() {
    assertThatThrownBy(() -> service.resetPassword("a1", null, "a1")).isInstanceOf(ValidationException.class);
  }

  private AuditEntry captureAudit() {
    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    return audit.getValue();
  }

  private static AdminUser admin(String id, boolean active) {
    return AdminUser.builder().id(id).email(id + "@stockkart.in").name(id).active(active).passwordHash("x").build();
  }
}
