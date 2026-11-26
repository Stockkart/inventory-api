package com.inventory.user.migration;

import com.inventory.common.audit.AuditUserType;
import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.user.domain.model.AdminUser;
import com.inventory.user.domain.repository.AdminUserRepository;
import com.inventory.user.service.AdminCredentials;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates an admin account on startup for each email in {@code platform.admin-emails} that has none.
 * Each new account gets its own random one-time password, written once to the startup log (WARN),
 * and must change it on first sign-in. Existing admins are never modified, and removing an email does
 * not remove the admin.
 */
@Component
@Slf4j
public class AdminBootstrap {

  static final String ACTION_BOOTSTRAPPED = "ADMIN_BOOTSTRAPPED";

  @Autowired
  private AdminUserRepository adminUserRepository;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private AuditService auditService;

  @Value("${platform.admin-emails:}")
  private String adminEmails;

  Clock clock = Clock.systemUTC();

  @EventListener(ApplicationReadyEvent.class)
  @Order(40)
  public void onStartup() {
    Set<String> emails = parse(adminEmails);
    if (emails.isEmpty()) {
      return;
    }
    for (String email : emails) {
      try {
        createIfMissing(email);
      } catch (RuntimeException e) {
        log.error("Admin bootstrap failed for {}: {}", email, e.getMessage(), e);
      }
    }
  }

  void createIfMissing(String email) {
    if (adminUserRepository.existsByEmail(email)) {
      return;
    }
    Instant now = clock.instant();
    String oneTimePassword = AdminCredentials.newTemporaryPassword();
    AdminUser saved;
    try {
      saved = adminUserRepository.save(AdminUser.builder()
          .email(email)
          .name(email.substring(0, email.indexOf('@')))
          .passwordHash(passwordEncoder.encode(oneTimePassword))
          .active(true)
          .mustChangePassword(true)
          .createdAt(now)
          .updatedAt(now)
          .build());
    } catch (DuplicateKeyException e) {
      return;
    }
    auditService.record(AuditEntry.builder()
        .action(ACTION_BOOTSTRAPPED)
        .targetType("ADMIN_USER")
        .targetId(saved.getId())
        .after(Map.of("email", email))
        .reason("platform.admin-emails")
        .actorType(AuditUserType.SYSTEM)
        .source(AuditSource.SYSTEM)
        .build());
    // Printed once, here only: the account must replace it at first sign-in
    log.warn("Created admin account {} for {} with one-time password {} (change it at first sign-in)",
        saved.getId(), email, oneTimePassword);
  }

  static Set<String> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(raw.split(","))
        .map(AdminCredentials::normalizeEmail)
        .filter(s -> s != null && s.contains("@"))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
