package com.inventory.user.service;

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
import com.inventory.user.rest.dto.response.AdminLoginResponse;
import com.inventory.user.rest.dto.response.AdminUserResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Sign-in and sessions for admin accounts. Shop-user tokens are never accepted here. */
@Service
@Slf4j
public class AdminAuthService {

  public static final Duration SESSION_LENGTH = Duration.ofHours(12);
  static final int MAX_FAILED_ATTEMPTS = 5;
  static final Duration LOCKOUT = Duration.ofMinutes(15);
  static final String BAD_CREDENTIALS = "Incorrect email or password";
  static final String LOCKED = "Too many failed attempts. Try again in 15 minutes.";

  @Autowired
  private AdminUserRepository adminUserRepository;

  @Autowired
  private AdminSessionRepository sessionRepository;

  @Autowired
  private AdminUserWriter adminUserWriter;

  @Autowired
  private PasswordEncoder passwordEncoder;

  Clock clock = Clock.systemUTC();

  public AdminLoginResponse login(AdminLoginRequest request) {
    String email = AdminCredentials.normalizeEmail(request == null ? null : request.getEmail());
    String password = request == null ? null : request.getPassword();
    if (email == null || password == null || password.isEmpty()) {
      throw new AuthenticationException(ErrorCode.INVALID_CREDENTIALS, BAD_CREDENTIALS);
    }
    Instant now = clock.instant();
    Optional<AdminUser> found = adminUserRepository.findByEmail(email);
    if (found.isEmpty()) {
      throw new AuthenticationException(ErrorCode.INVALID_CREDENTIALS, BAD_CREDENTIALS);
    }
    AdminUser admin = found.get();
    if (admin.isLockedAt(now)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, LOCKED);
    }
    if (!passwordEncoder.matches(password, admin.getPasswordHash())) {
      boolean locked = adminUserWriter.recordFailedLogin(admin.getId(), now, MAX_FAILED_ATTEMPTS, LOCKOUT);
      if (locked) {
        log.warn("Admin {} locked after {} failed sign-ins", admin.getId(), MAX_FAILED_ATTEMPTS);
        throw new AuthenticationException(ErrorCode.UNAUTHORIZED, LOCKED);
      }
      throw new AuthenticationException(ErrorCode.INVALID_CREDENTIALS, BAD_CREDENTIALS);
    }
    if (!admin.isActive()) {
      throw new AuthenticationException(ErrorCode.ACCOUNT_DISABLED, "This admin account is turned off");
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
  }

  /** Sets the admin's own password and signs out their other sessions. */
  public AdminUserResponse changePassword(AdminAuthentication auth, AdminChangePasswordRequest request) {
    AdminUser admin = auth.admin();
    String current = request == null ? null : request.getCurrentPassword();
    String next = request == null ? null : request.getNewPassword();
    if (current == null || !passwordEncoder.matches(current, admin.getPasswordHash())) {
      throw new ValidationException("Current password is incorrect");
    }
    AdminCredentials.requireAcceptablePassword(next);
    if (passwordEncoder.matches(next, admin.getPasswordHash())) {
      throw new ValidationException("Choose a password different from the current one");
    }
    adminUserWriter.setPassword(admin.getId(), passwordEncoder.encode(next), false, clock.instant());
    sessionRepository.deleteByAdminIdAndIdNot(admin.getId(), auth.session().getId());
    admin.setMustChangePassword(false);
    return AdminUserMapper.toResponse(admin);
  }

  private static AuthenticationException signInAgain() {
    return new AuthenticationException(ErrorCode.UNAUTHORIZED, "Admin session is missing or expired; sign in again");
  }
}
