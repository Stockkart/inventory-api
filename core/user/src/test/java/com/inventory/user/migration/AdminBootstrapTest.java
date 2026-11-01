package com.inventory.user.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.user.domain.model.AdminUser;
import com.inventory.user.domain.repository.AdminUserRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

  @Mock
  private AdminUserRepository adminUserRepository;

  @Mock
  private AuditService auditService;

  @Spy
  private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

  @InjectMocks
  private AdminBootstrap bootstrap;

  @Test
  void parsesTrimsLowercasesAndDedupes() {
    assertThat(AdminBootstrap.parse(" Ops@StockKart.in, ,ops@stockkart.in,dev@x.io,junk "))
        .containsExactly("ops@stockkart.in", "dev@x.io");
    assertThat(AdminBootstrap.parse("")).isEmpty();
    assertThat(AdminBootstrap.parse(null)).isEmpty();
  }

  @Test
  void createsMissingAdminsWithAForcedPasswordChange() {
    configure("ops@stockkart.in,dev@x.io", "bootstrap-pass-1");
    when(adminUserRepository.existsByEmail("ops@stockkart.in")).thenReturn(false);
    when(adminUserRepository.existsByEmail("dev@x.io")).thenReturn(true);
    when(adminUserRepository.save(any(AdminUser.class))).thenAnswer(i -> {
      AdminUser admin = i.getArgument(0);
      admin.setId("a1");
      return admin;
    });

    bootstrap.onStartup();

    ArgumentCaptor<AdminUser> saved = ArgumentCaptor.forClass(AdminUser.class);
    verify(adminUserRepository, times(1)).save(saved.capture());
    assertThat(saved.getValue().getEmail()).isEqualTo("ops@stockkart.in");
    assertThat(saved.getValue().getName()).isEqualTo("ops");
    assertThat(saved.getValue().isActive()).isTrue();
    assertThat(saved.getValue().isMustChangePassword()).isTrue();
    assertThat(passwordEncoder.matches("bootstrap-pass-1", saved.getValue().getPasswordHash())).isTrue();

    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertThat(audit.getValue().getAction()).isEqualTo(AdminBootstrap.ACTION_BOOTSTRAPPED);
    assertThat(audit.getValue().getSource()).isEqualTo(AuditSource.SYSTEM);
  }

  @Test
  void createsNothingWithoutAUsablePassword() {
    configure("ops@stockkart.in", "");
    bootstrap.onStartup();
    configure("ops@stockkart.in", "short");
    bootstrap.onStartup();

    verifyNoInteractions(adminUserRepository, auditService);
  }

  @Test
  void doesNothingWhenNoEmailsConfigured() {
    configure("", "bootstrap-pass-1");

    bootstrap.onStartup();

    verifyNoInteractions(adminUserRepository, auditService);
  }

  @Test
  void concurrentInstanceWinningTheInsertIsNotAnError() {
    configure("ops@stockkart.in", "bootstrap-pass-1");
    when(adminUserRepository.save(any(AdminUser.class))).thenThrow(new DuplicateKeyException("dup"));

    bootstrap.onStartup();

    verify(auditService, never()).record(any());
  }

  private void configure(String emails, String password) {
    ReflectionTestUtils.setField(bootstrap, "adminEmails", emails);
    ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", password);
  }
}
