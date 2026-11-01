package com.inventory.user.service;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
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
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Admins managing other admins. Every change is audited; passwords never are. */
@Service
public class AdminUserService {

  static final String TARGET_TYPE = "ADMIN_USER";
  static final String ACTION_CREATED = "ADMIN_CREATED";
  static final String ACTION_ENABLED = "ADMIN_ENABLED";
  static final String ACTION_DISABLED = "ADMIN_DISABLED";
  static final String ACTION_PASSWORD_RESET = "ADMIN_PASSWORD_RESET";
  static final int MAX_REASON_LENGTH = 200;

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

  Clock clock = Clock.systemUTC();

  public List<AdminUserResponse> list() {
    return adminUserRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(AdminUserMapper::toResponse)
        .toList();
  }

  public AdminPasswordIssuedResponse create(AdminCreateRequest request, String actorAdminId) {
    String email = AdminCredentials.requireEmail(request == null ? null : request.getEmail());
    String name = AdminCredentials.requireName(request.getName());
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
    if (request == null || request.getActive() == null) {
      throw new ValidationException("active is required");
    }
    boolean active = request.getActive();
    String reason = reason(request.getReason());
    AdminUser target = find(adminId);
    if (target.isActive() == active) {
      return AdminUserMapper.toResponse(target);
    }
    if (!active) {
      if (adminId.equals(actorAdminId)) {
        throw new ValidationException("You cannot turn off your own account");
      }
      if (adminUserRepository.countByActiveTrue() <= 1) {
        throw new ValidationException("At least one admin must stay active");
      }
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

  public AdminPasswordIssuedResponse resetPassword(String adminId, AdminUserReasonRequest request,
      String actorAdminId) {
    if (adminId.equals(actorAdminId)) {
      throw new ValidationException("Use Change password for your own account");
    }
    String reason = reason(request == null ? null : request.getReason());
    AdminUser target = find(adminId);
    String temporaryPassword = AdminCredentials.newTemporaryPassword();
    adminUserWriter.setPassword(adminId, passwordEncoder.encode(temporaryPassword), true, clock.instant());
    sessionRepository.deleteByAdminId(adminId);
    audit(ACTION_PASSWORD_RESET, adminId, actorAdminId, null, Map.of("mustChangePassword", true), reason);
    target.setMustChangePassword(true);
    return new AdminPasswordIssuedResponse(AdminUserMapper.toResponse(target), temporaryPassword);
  }

  private AdminUser find(String adminId) {
    return adminUserRepository.findById(adminId)
        .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND, "Admin not found"));
  }

  private void audit(String action, String targetId, String actorAdminId, Map<String, Object> before,
      Map<String, Object> after, String reason) {
    auditService.record(AuditEntry.builder()
        .actorUserId(actorAdminId)
        .action(action)
        .targetType(TARGET_TYPE)
        .targetId(targetId)
        .before(before)
        .after(after)
        .reason(reason)
        .source(AuditSource.ADMIN_UI)
        .build());
  }

  private static String reason(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.length() > MAX_REASON_LENGTH) {
      throw new ValidationException("Reason must be at most " + MAX_REASON_LENGTH + " characters");
    }
    return trimmed;
  }

  private static ValidationException duplicateEmail() {
    return new ValidationException("An admin with this email already exists");
  }
}
