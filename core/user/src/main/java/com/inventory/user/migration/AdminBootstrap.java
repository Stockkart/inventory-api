package com.inventory.user.migration;

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
 * Creates an admin account on startup for each email in {@code platform.admin-emails} that has none,
 * using {@code platform.admin-bootstrap-password} and forcing a password change on first sign-in.
 * Existing admins are never modified, and removing an email does not remove the admin.
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

  @Value("${platform.admin-bootstrap-password:}")
  private String bootstrapPassword;

  Clock clock = Clock.systemUTC();

  @EventListener(ApplicationReadyEvent.class)
  @Order(40)
  public void onStartup() {
    Set<String> emails = parse(adminEmails);
    if (emails.isEmpty()) {
      return;
    }
    if (bootstrapPassword == null || bootstrapPassword.length() < AdminCredentials.MIN_PASSWORD_LENGTH
        || bootstrapPassword.length() > AdminCredentials.MAX_PASSWORD_LENGTH) {
      log.warn("PLATFORM_ADMIN_EMAILS is set but PLATFORM_ADMIN_BOOTSTRAP_PASSWORD is missing or not {}-{} "
              + "characters; no admin accounts were created",
          AdminCredentials.MIN_PASSWORD_LENGTH, AdminCredentials.MAX_PASSWORD_LENGTH);
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
    AdminUser saved;
    try {
      saved = adminUserRepository.save(AdminUser.builder()
          .email(email)
          .name(email.substring(0, email.indexOf('@')))
          .passwordHash(passwordEncoder.encode(bootstrapPassword))
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
        .source(AuditSource.SYSTEM)
        .build());
    log.info("Created admin account {} for {}", saved.getId(), email);
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
