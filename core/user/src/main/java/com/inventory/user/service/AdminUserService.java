package com.inventory.user.service;

import com.inventory.common.audit.AuditUserType;
import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.notifications.api.Messaging;
import com.inventory.user.domain.model.AdminUser;
import com.inventory.user.domain.repository.AdminSessionRepository;
import com.inventory.user.domain.repository.AdminUserRepository;
import com.inventory.user.domain.repository.AdminUserWriter;
import com.inventory.user.mapper.AdminUserMapper;
import com.inventory.user.rest.dto.request.AdminCreateRequest;
import com.inventory.user.rest.dto.request.AdminUserActiveRequest;
import com.inventory.user.rest.dto.request.AdminUserReasonRequest;
import com.inventory.user.rest.dto.response.AdminPasswordIssuedResponse;
import com.inventory.user.rest.dto.response.AdminUserResponse;
import com.inventory.user.validation.AdminUserValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Admins managing other admins. Every change is audited (actor and target); passwords never are.
 *
 * <p>A password reset never hands a password to the admin who asks for it: it signs the target out,
 * makes the old password stop working and emails the target a one-time link to choose a new one, so
 * only the account owner ever knows the password.
 */
@Service
public class AdminUserService {

  static final String TARGET_TYPE = "ADMIN_USER";
  static final String ACTION_CREATED = "ADMIN_CREATED";
  static final String ACTION_ENABLED = "ADMIN_ENABLED";
  static final String ACTION_DISABLED = "ADMIN_DISABLED";
  static final String ACTION_PASSWORD_RESET = "ADMIN_PASSWORD_RESET";
  /** How long the emailed reset link works. */
  static final Duration RESET_LINK_LIFETIME = Duration.ofMinutes(30);

  @Autowired
  private AdminUserRepository adminUserRepository;

  @Autowired
  private AdminSessionRepository sessionRepository;

  @Autowired
  private AdminUserWriter adminUserWriter;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private AuditService auditService;

  @Autowired
  private AdminUserValidator adminUserValidator;

  @Autowired
  private Messaging messaging;

  /** Admin app origin; reset links point at {@code <admin.client.url>/reset-password}. */
  @Value("${admin.client.url:}")
  String adminClientUrl = "";

  Clock clock = Clock.systemUTC();

  public List<AdminUserResponse> list() {
    return adminUserRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(AdminUserMapper::toResponse)
        .toList();
  }

  public AdminPasswordIssuedResponse create(AdminCreateRequest request, String actorAdminId) {
    adminUserValidator.validateCreate(request);
    String email = AdminCredentials.normalizeEmail(request.getEmail());
    String name = request.getName().trim();
    if (adminUserRepository.existsByEmail(email)) {
      throw duplicateEmail();
    }
    Instant now = clock.instant();
    String temporaryPassword = AdminCredentials.newTemporaryPassword();
    AdminUser saved;
    try {
      saved = adminUserRepository.save(AdminUser.builder()
          .email(email)
          .name(name)
          .passwordHash(passwordEncoder.encode(temporaryPassword))
          .active(true)
          .mustChangePassword(true)
          .createdByAdminId(actorAdminId)
          .createdAt(now)
          .updatedAt(now)
          .build());
    } catch (DuplicateKeyException e) {
      throw duplicateEmail();
    }
    audit(ACTION_CREATED, saved.getId(), actorAdminId, null, Map.of("email", email, "name", name), null);
    return new AdminPasswordIssuedResponse(AdminUserMapper.toResponse(saved), temporaryPassword);
  }

  public AdminUserResponse setActive(String adminId, AdminUserActiveRequest request, String actorAdminId) {
    adminUserValidator.validateSetActive(request);
    boolean active = request.getActive();
    String reason = reason(request.getReason());
    AdminUser target = find(adminId);
    if (target.isActive() == active) {
      return AdminUserMapper.toResponse(target);
    }
    if (!active) {
      adminUserValidator.validateDisable(adminId, actorAdminId, adminUserRepository.countByActiveTrue());
    }
    adminUserWriter.setActive(adminId, active, clock.instant());
    if (!active) {
      sessionRepository.deleteByAdminId(adminId);
    }
    audit(active ? ACTION_ENABLED : ACTION_DISABLED, adminId, actorAdminId,
        Map.of("active", !active), Map.of("active", active), reason);
    target.setActive(active);
    return AdminUserMapper.toResponse(target);
  }

  /**
   * Starts a password reset for another admin: their sessions end, their current password stops
   * working, and a one-time link (valid {@link #RESET_LINK_LIFETIME}) is emailed to their address.
   * The response carries no credential.
   */
  public AdminUserResponse resetPassword(String adminId, AdminUserReasonRequest request, String actorAdminId) {
    String rawReason = request == null ? null : request.getReason();
    adminUserValidator.validateReset(adminId, actorAdminId, rawReason);
    if (adminClientUrl == null || adminClientUrl.isBlank()) {
      throw new ValidationException("Password reset emails need the admin app address (ADMIN_CLIENT_URL)");
    }
    AdminUser target = find(adminId);
    Instant now = clock.instant();
    String token = AdminCredentials.newSessionToken();
    adminUserWriter.startPasswordReset(adminId, passwordEncoder.encode(AdminCredentials.newSessionToken()),
        AdminCredentials.hashToken(token), now.plus(RESET_LINK_LIFETIME), now);
    sessionRepository.deleteByAdminId(adminId);
    String link = adminClientUrl.replaceAll("/+$", "") + "/reset-password?token=" + token;
    messaging.sendEmail(target.getEmail(), "Set a new StockKart admin password",
        "<p>Hello " + escape(target.getName()) + ",</p>"
            + "<p>Another StockKart admin started a password reset for your admin account. Your old "
            + "password no longer works. Choose a new one within 30 minutes:</p>"
            + "<p><a href=\"" + link + "\">Set a new password</a></p>"
            + "<p>If you did not expect this, tell the StockKart team.</p>",
        Map.of("kind", ACTION_PASSWORD_RESET, "adminId", adminId));
    audit(ACTION_PASSWORD_RESET, adminId, actorAdminId, null,
        Map.of("resetLinkEmailed", true, "resetLinkExpiresAt", now.plus(RESET_LINK_LIFETIME).toString()),
        reason(rawReason));
    target.setMustChangePassword(false);
    return AdminUserMapper.toResponse(target);
  }

  private AdminUser find(String adminId) {
    return adminUserRepository.findById(adminId)
        .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND, "Admin not found"));
  }

  private void audit(String action, String targetId, String actorAdminId, Map<String, Object> before,
      Map<String, Object> after, String reason) {
    auditService.record(AuditEntry.builder()
        .actorUserId(actorAdminId)
        .actorType(AuditUserType.ADMIN)
        .action(action)
        .targetType(TARGET_TYPE)
        .targetId(targetId)
        .before(before)
        .after(after)
        .reason(reason)
        .source(AuditSource.ADMIN_UI)
        .build());
  }

  /** Trimmed reason, or null when blank; the length is checked by {@link AdminUserValidator}. */
  private static String reason(String raw) {
    return raw == null || raw.isBlank() ? null : raw.trim();
  }

  private static String escape(String text) {
    return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static ValidationException duplicateEmail() {
    return new ValidationException("An admin with this email already exists");
  }
}
