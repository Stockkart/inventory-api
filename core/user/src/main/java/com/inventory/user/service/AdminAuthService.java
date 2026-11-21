package com.inventory.user.service;

import com.inventory.common.audit.AuditActorType;
import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.AdminSession;
import com.inventory.user.domain.model.AdminUser;
import com.inventory.user.domain.repository.AdminSessionRepository;
import com.inventory.user.domain.repository.AdminUserRepository;
import com.inventory.user.domain.repository.AdminUserWriter;
import com.inventory.user.mapper.AdminUserMapper;
import com.inventory.user.rest.dto.request.AdminChangePasswordRequest;
import com.inventory.user.rest.dto.request.AdminLoginRequest;
import com.inventory.user.rest.dto.request.AdminResetPasswordRequest;
import com.inventory.user.rest.dto.response.AdminLoginResponse;
import com.inventory.user.rest.dto.response.AdminUserResponse;
import com.inventory.user.validation.AdminUserValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Sign-in and sessions for admin accounts. Shop-user tokens are never accepted here.
 *
 * <p>Every refused sign-in gets the same message and the same work (a password hash check runs even
 * for an unknown email), so the answer does not tell whether an email is an admin, is turned off or
 * is locked. {@link AdminSignInLimiter} limits failures per client address before any check runs.
 */
@Service
@Slf4j
public class AdminAuthService {

  public static final Duration SESSION_LENGTH = Duration.ofHours(12);
  /** Stored lock: a backstop against many addresses guessing one account. */
  static final int MAX_FAILED_ATTEMPTS = 20;
  static final Duration LOCKOUT = Duration.ofMinutes(15);
  static final String BAD_CREDENTIALS = "Sign-in failed. Check your email and password, or try again later.";
  static final String RESET_INVALID = "The reset link is invalid or has expired";

  static final String TARGET_TYPE = "ADMIN_USER";
  static final String ACTION_SIGNED_IN = "ADMIN_SIGNED_IN";
  static final String ACTION_SIGN_IN_FAILED = "ADMIN_SIGN_IN_FAILED";
  static final String ACTION_LOCKED = "ADMIN_LOCKED";
  static final String ACTION_SIGNED_OUT = "ADMIN_SIGNED_OUT";
  static final String ACTION_PASSWORD_CHANGED = "ADMIN_PASSWORD_CHANGED";
  static final String ACTION_PASSWORD_RESET_COMPLETED = "ADMIN_PASSWORD_RESET_COMPLETED";

  @Autowired
  private AdminUserRepository adminUserRepository;

  @Autowired
  private AdminSessionRepository sessionRepository;

  @Autowired
  private AdminUserWriter adminUserWriter;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private AdminSignInLimiter signInLimiter;

  @Autowired
  private AdminUserValidator adminUserValidator;

  @Autowired
  private AuditService auditService;

  Clock clock = Clock.systemUTC();

  /** Hash of a random secret, checked for unknown emails so they cost as much as known ones. */
  private volatile String dummyHash;

  public AdminLoginResponse login(AdminLoginRequest request, String clientAddress) {
    String email = AdminCredentials.normalizeEmail(request == null ? null : request.getEmail());
    String password = request == null || request.getPassword() == null ? "" : request.getPassword();
    signInLimiter.checkAllowed(clientAddress, email);
    Instant now = clock.instant();

    Optional<AdminUser> found = email == null ? Optional.empty() : adminUserRepository.findByEmail(email);
    if (found.isEmpty()) {
      passwordEncoder.matches(password, dummyHash());
      throw refused(clientAddress, email);
    }
    AdminUser admin = found.get();
    boolean passwordMatches = !password.isEmpty() && passwordEncoder.matches(password, admin.getPasswordHash());
    if (admin.isLockedAt(now)) {
      // not counted again: the lock already holds
      throw refused(clientAddress, email);
    }
    if (!passwordMatches) {
      boolean locked = adminUserWriter.recordFailedLogin(admin.getId(), now, MAX_FAILED_ATTEMPTS, LOCKOUT);
      auditSystem(locked ? ACTION_LOCKED : ACTION_SIGN_IN_FAILED, admin.getId());
      if (locked) {
        log.warn("Admin {} locked for {} after {} failed sign-ins", admin.getId(), LOCKOUT, MAX_FAILED_ATTEMPTS);
      }
      throw refused(clientAddress, email);
    }
    if (!admin.isActive()) {
      throw refused(clientAddress, email);
    }

    adminUserWriter.recordSuccessfulLogin(admin.getId(), now);
    admin.setLastLoginAt(now);
    String token = AdminCredentials.newSessionToken();
    AdminSession session = sessionRepository.save(AdminSession.builder()
        .tokenHash(AdminCredentials.hashToken(token))
        .adminId(admin.getId())
        .createdAt(now)
        .expiresAt(now.plus(SESSION_LENGTH))
        .build());
    auditAdmin(ACTION_SIGNED_IN, admin.getId(), admin.getId(), null);
    return new AdminLoginResponse(token, session.getExpiresAt(), AdminUserMapper.toResponse(admin));
  }

  /** Resolves a bearer token to an active admin's live session, or throws 401. */
  public AdminAuthentication authenticate(String token) {
    if (token == null || token.isBlank()) {
      throw signInAgain();
    }
    AdminSession session = sessionRepository.findByTokenHash(AdminCredentials.hashToken(token))
        .orElseThrow(AdminAuthService::signInAgain);
    if (session.isExpiredAt(clock.instant())) {
      sessionRepository.deleteById(session.getId());
      throw signInAgain();
    }
    AdminUser admin = adminUserRepository.findById(session.getAdminId())
        .filter(AdminUser::isActive)
        .orElseThrow(AdminAuthService::signInAgain);
    return new AdminAuthentication(admin, session);
  }

  public void logout(AdminAuthentication auth) {
    sessionRepository.deleteById(auth.session().getId());
    auditAdmin(ACTION_SIGNED_OUT, auth.adminId(), auth.adminId(), null);
  }

  /** Sets the admin's own password and signs out their other sessions. */
  public AdminUserResponse changePassword(AdminAuthentication auth, AdminChangePasswordRequest request) {
    AdminUser admin = auth.admin();
    adminUserValidator.validateChangePassword(request);
    if (!passwordEncoder.matches(request.getCurrentPassword(), admin.getPasswordHash())) {
      throw new ValidationException("Current password is incorrect");
    }
    String next = request.getNewPassword();
    if (passwordEncoder.matches(next, admin.getPasswordHash())) {
      throw new ValidationException("Choose a password different from the current one");
    }
    adminUserWriter.setPassword(admin.getId(), passwordEncoder.encode(next), false, clock.instant());
    sessionRepository.deleteByAdminIdAndIdNot(admin.getId(), auth.session().getId());
    auditAdmin(ACTION_PASSWORD_CHANGED, admin.getId(), admin.getId(), null);
    admin.setMustChangePassword(false);
    return AdminUserMapper.toResponse(admin);
  }

  /**
   * Sets a new password with the one-time token from a reset email. The token works once and only
   * until it expires; every session of the account is signed out. The account owner is the actor.
   */
  public AdminUserResponse completePasswordReset(AdminResetPasswordRequest request, String clientAddress) {
    signInLimiter.checkAllowed(clientAddress, null);
    adminUserValidator.validateResetCompletion(request);
    Instant now = clock.instant();
    String tokenHash = AdminCredentials.hashToken(request.getToken().trim());
    AdminUser admin = adminUserRepository.findByResetTokenHash(tokenHash)
        .filter(a -> a.getResetTokenExpiresAt() != null && a.getResetTokenExpiresAt().isAfter(now))
        .filter(AdminUser::isActive)
        .orElse(null);
    if (admin == null
        || !adminUserWriter.completePasswordReset(admin.getId(), tokenHash,
            passwordEncoder.encode(request.getNewPassword()), now)) {
      signInLimiter.recordFailure(clientAddress, null);
      throw new ValidationException(RESET_INVALID);
    }
    sessionRepository.deleteByAdminId(admin.getId());
    auditAdmin(ACTION_PASSWORD_RESET_COMPLETED, admin.getId(), admin.getId(), null);
    admin.setMustChangePassword(false);
    return AdminUserMapper.toResponse(admin);
  }

  private AuthenticationException refused(String clientAddress, String email) {
    signInLimiter.recordFailure(clientAddress, email);
    return new AuthenticationException(ErrorCode.INVALID_CREDENTIALS, BAD_CREDENTIALS);
  }

  private String dummyHash() {
    String hash = dummyHash;
    if (hash == null) {
      hash = passwordEncoder.encode(AdminCredentials.newSessionToken());
      dummyHash = hash;
    }
    return hash;
  }

  private void auditAdmin(String action, String targetAdminId, String actorAdminId, Map<String, Object> after) {
    auditService.record(AuditEntry.builder()
        .actorUserId(actorAdminId)
        .actorType(AuditActorType.ADMIN)
        .action(action)
        .targetType(TARGET_TYPE)
        .targetId(targetAdminId)
        .after(after)
        .source(AuditSource.ADMIN_UI)
        .build());
  }

  /** Failed sign-ins: nobody is signed in, so the entry has no actor. */
  private void auditSystem(String action, String targetAdminId) {
    auditService.record(AuditEntry.builder()
        .actorType(AuditActorType.SYSTEM)
        .action(action)
        .targetType(TARGET_TYPE)
        .targetId(targetAdminId)
        .source(AuditSource.SYSTEM)
        .build());
  }

  private static AuthenticationException signInAgain() {
    return new AuthenticationException(ErrorCode.UNAUTHORIZED, "Admin session is missing or expired; sign in again");
  }
}
