package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserInvite;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserInviteRepository;
import com.inventory.user.rest.dto.auth.AcceptInviteRequest;
import com.inventory.user.rest.dto.auth.AcceptInviteResponse;
import com.inventory.user.rest.dto.auth.LoginRequest;
import com.inventory.user.rest.dto.auth.LoginResponse;
import com.inventory.user.rest.dto.auth.SignupRequest;
import com.inventory.user.rest.dto.auth.SignupResponse;
import com.inventory.user.rest.mapper.UserMapper;
import com.inventory.user.validation.AuthValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Slf4j
public class AuthService {

  @Autowired
  private UserAccountRepository userAccountRepository;

  @Autowired
  private UserInviteRepository userInviteRepository;

  @Autowired
  private UserMapper userMapper;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private AuthValidator authValidator;

  @Transactional(readOnly = true)
  public LoginResponse login(LoginRequest request) {
    try {
      // Validate login request
      authValidator.validateLoginRequest(request);

      log.debug("Attempting login for email: {}", request.getEmail());

      // Find user by email and verify password
      UserAccount account = userAccountRepository.findByEmail(request.getEmail())
              .filter(user -> {
                if (user.getPassword() == null) {
                  log.warn("Login attempt for user with no password set: {}", request.getEmail());
                  return false;
                }
                return passwordEncoder.matches(request.getPassword(), user.getPassword());
              })
              .orElseThrow(() -> new AuthenticationException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password"));

      // Check if account is active
      if (!account.isActive()) {
        log.warn("Login attempt for deactivated account: {}", request.getEmail());
        throw new AuthenticationException(ErrorCode.ACCOUNT_DISABLED, "Account is deactivated");
      }

      log.info("User logged in successfully: {}", account.getUserId());

      // Create login response using mapper (tokens set automatically via @AfterMapping and saved to account)
      LoginResponse response = userMapper.toLoginResponse(account, request.getDeviceId());
      
      // Save account with new token
      userAccountRepository.save(account);
      
      return response;

    } catch (ValidationException | AuthenticationException e) {
      log.warn("Login failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error during login for email {}: {}", request != null ? request.getEmail() : "null", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error during login");
    } catch (Exception e) {
      log.error("Unexpected error during login: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }

  @Transactional
  public AcceptInviteResponse acceptAdminInvite(AcceptInviteRequest request) {
    try {
      // Validate request
      authValidator.validateAcceptInviteRequest(request);

      log.debug("Processing admin invite acceptance for token: {}", request.getInviteToken());

      // Find and validate invite
      UserInvite invite = userInviteRepository.findByToken(request.getInviteToken())
              .orElseThrow(() -> new ResourceNotFoundException("Invite", "token", request.getInviteToken()));

      // Validate invite
      authValidator.validateInvite(invite);

      // Check if invite is already accepted
      if (invite.isAccepted()) {
        log.warn("Attempted to use already accepted invite token: {}", request.getInviteToken());
        throw new ValidationException("This invite has already been used");
      }

      // Check if invite is expired
      if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(Instant.now())) {
        log.warn("Attempted to use expired invite token: {}", request.getInviteToken());
        throw new ValidationException("This invite has expired");
      }


      // Mark invite as accepted
      invite.setAccepted(true);
      userInviteRepository.save(invite);

      // Check if user already exists
      UserAccount account = userAccountRepository.findByEmail(invite.getEmail())
              .orElseGet(() -> {
                // Create new user account from invite using mapper
                return userMapper.toUserAccount(invite, passwordEncoder, request.getPassword());
              });

      // Update account details using mapper
      userMapper.updateUserAccountFromInvite(account, invite, passwordEncoder, request.getPassword());

      userAccountRepository.save(account);

      log.info("Admin invite accepted successfully for user: {}", account.getUserId());

      // Map to response using mapper
      return userMapper.toAcceptInviteResponse(account);

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Failed to accept admin invite: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while accepting admin invite: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error processing invite acceptance");
    } catch (Exception e) {
      log.error("Unexpected error while accepting admin invite: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }

  @Transactional
  public SignupResponse signup(SignupRequest request) {
    try {
      // Validate signup request
      authValidator.validateSignupRequest(request);

      log.debug("Attempting signup for email: {}", request.getEmail());

      // Check if user already exists
      if (userAccountRepository.findByEmail(request.getEmail()).isPresent()) {
        log.warn("Signup attempt with existing email: {}", request.getEmail());
        throw new ValidationException("User with this email already exists");
      }

      // Map SignupRequest to UserAccount using mapper (with password encoder context)
      UserAccount account = userMapper.toUserAccount(request, passwordEncoder);

      userAccountRepository.save(account);

      log.info("User signed up successfully: {}", account.getUserId());

      // Create signup response using mapper (tokens set automatically via @AfterMapping and saved to account)
      SignupResponse response = userMapper.toSignupResponse(account, request.getDeviceId());
      
      // Save account with new token
      userAccountRepository.save(account);
      
      return response;

    } catch (ValidationException e) {
      log.warn("Signup failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error during signup for email {}: {}", request != null ? request.getEmail() : "null", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error during signup");
    } catch (Exception e) {
      log.error("Unexpected error during signup: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }
}

