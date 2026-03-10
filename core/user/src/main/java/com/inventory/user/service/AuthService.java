package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.externalservice.GoogleTokenVerifier;
import com.inventory.user.mapper.UserMapper;
import com.inventory.user.rest.dto.request.ChangePasswordRequest;
import com.inventory.user.rest.dto.request.LoginRequest;
import com.inventory.user.rest.dto.request.SignupRequest;
import com.inventory.user.rest.dto.response.ChangePasswordResponse;
import com.inventory.user.rest.dto.response.LoginResponse;
import com.inventory.user.rest.dto.response.LogoutResponse;
import com.inventory.user.rest.dto.response.SignupResponse;
import com.inventory.user.validation.AuthValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
@Slf4j
public class AuthService {

  @Autowired
  private UserAccountRepository userAccountRepository;

  @Autowired
  private UserMapper userMapper;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private AuthValidator authValidator;

  @Autowired
  private GoogleTokenVerifier googleTokenVerifier;

  @Autowired
  private TokenValidationService tokenValidationService;

  @Autowired(required = false)
  private ShopServiceAdapter shopServiceAdapter;

  @Transactional(readOnly = true)
  public LoginResponse login(LoginRequest request) {
    try {
      // Validate login request
      authValidator.validateLoginRequest(request);

      UserAccount account;

      // Check if OAuth ID token is provided (authValidator validates loginType when idToken present)
      if (request.getIdToken() != null && !request.getIdToken().trim().isEmpty()) {
        String loginType = request.getLoginType() != null ? request.getLoginType().toLowerCase() : null;
        String email;
        Map<String, Object> payload;

        if ("google".equals(loginType)) {
          // Google authentication
          log.debug("Attempting Google login");

          // Verify Google ID token
          payload = googleTokenVerifier.verifyToken(request.getIdToken());
          email = googleTokenVerifier.getEmail(payload);

          log.debug("Google login for email: {}", email);
        } else {
          throw new ValidationException("Invalid loginType. Must be 'google' or 'facebook'");
        }
        // Find user by email
        String providerName = loginType.substring(0, 1).toUpperCase() + loginType.substring(1);
        account = userAccountRepository.findByEmail(email)
            .orElseThrow(() -> new AuthenticationException(ErrorCode.INVALID_CREDENTIALS,
                "No account found for this " + providerName + " account. Please sign up first."));

      } else {
        // Email/password authentication
        log.debug("Attempting email/password login for email: {}", request.getEmail());

        // Find user by email and verify password
        account = userAccountRepository.findByEmail(request.getEmail())
            .filter(user -> {
              if (user.getPassword() == null) {
                log.warn("Login attempt for user with no password set: {}", request.getEmail());
                return false;
              }
              return passwordEncoder.matches(request.getPassword(), user.getPassword());
            })
            .orElseThrow(() -> new AuthenticationException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password"));
      }

      // Check if account is active
      if (!account.isActive()) {
        log.warn("Login attempt for deactivated account: {}", account.getEmail());
        throw new AuthenticationException(ErrorCode.ACCOUNT_DISABLED, "Account is deactivated");
      }

      log.info("User logged in successfully: {}", account.getUserId());

      // Create login response using mapper (tokens set automatically via @AfterMapping and saved to account)
      LoginResponse response = userMapper.toLoginResponse(account, request.getDeviceId());

      // Populate shop information if shopId exists and adapter is available
      if (account.getShopId() != null && !account.getShopId().trim().isEmpty() && shopServiceAdapter != null) {
        ShopServiceAdapter.ShopTaxInfo taxInfo = shopServiceAdapter.getShopTaxInfo(account.getShopId());
        if (taxInfo != null) {
          response.setShop(userMapper.toShopInfo(taxInfo));
        }
      }

      // Save account with new token
      userAccountRepository.save(account);

      return response;

    } catch (ValidationException | AuthenticationException e) {
      log.warn("Login failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error during login: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error during login");
    } catch (Exception e) {
      log.error("Unexpected error during login: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }

  @Transactional
  public SignupResponse signup(SignupRequest request) {
    try {
      // Validate signup request
      authValidator.validateSignupRequest(request);

      UserAccount account;
      String email;

      // Check if OAuth ID token is provided (authValidator validates signupType when idToken present)
      if (request.getIdToken() != null && !request.getIdToken().trim().isEmpty()) {
        String signupType = request.getSignupType() != null ? request.getSignupType().toLowerCase() : null;
        Map<String, Object> payload;
        String name;

        if ("google".equals(signupType)) {
          // Google signup
          log.debug("Attempting Google signup");

          // Verify Google ID token
          payload = googleTokenVerifier.verifyToken(request.getIdToken());
          email = googleTokenVerifier.getEmail(payload);
          name = googleTokenVerifier.getName(payload);

          log.debug("Google signup for email: {}", email);
        } else {
          throw new ValidationException("Invalid signupType. Must be 'google' or 'facebook'");
        }

        // Check if user already exists
        if (userAccountRepository.findByEmail(email).isPresent()) {
          String providerName = signupType.substring(0, 1).toUpperCase() + signupType.substring(1);
          log.warn("{} signup attempt with existing email: {}", providerName, email);
          throw new ValidationException("User with this email already exists. Please login instead.");
        }

        // Create user account from OAuth data via mapper
        account = userMapper.toUserAccountFromOAuth(email, name, request);

      } else {
        // Email/password signup
        log.debug("Attempting email/password signup for email: {}", request.getEmail());
        email = request.getEmail();

        // Check if user already exists
        if (userAccountRepository.findByEmail(email).isPresent()) {
          log.warn("Signup attempt with existing email: {}", email);
          throw new ValidationException("User with this email already exists");
        }

        // Map SignupRequest to UserAccount using mapper (with password encoder context)
        account = userMapper.toUserAccount(request, passwordEncoder);
      }

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
      log.error("Database error during signup: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error during signup");
    } catch (Exception e) {
      log.error("Unexpected error during signup: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }

  @Transactional
  public LogoutResponse logout(String userId, String accessToken) {
    try {
      authValidator.validateLogoutParams(userId, accessToken);

      log.debug("Processing logout for user: {}", userId);

      // Find user account
      UserAccount account = userAccountRepository.findById(userId)
          .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

      // Remove the token using UserAccount method (using accessToken to find and remove)
      String removedDeviceId = account.removeToken(null, accessToken);

      if (removedDeviceId == null) {
        log.warn("No matching token found for logout - userId: {}", userId);
        throw new ValidationException("No matching token found to logout");
      }

      // Save account with updated tokens
      userAccountRepository.save(account);

      log.info("User logged out successfully: {}, deviceId: {}", account.getUserId(), removedDeviceId);

      // Create logout response using mapper
      return userMapper.toLogoutResponse(removedDeviceId);

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Logout failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error during logout for userId {}: {}", userId, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error during logout");
    } catch (Exception e) {
      log.error("Unexpected error during logout: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }

  @Transactional
  public ChangePasswordResponse changePassword(ChangePasswordRequest request) {
    authValidator.validateChangePasswordRequest(request);

    UserAccount account = userAccountRepository.findByEmail(request.getEmail().trim())
        .orElseThrow(() -> new ResourceNotFoundException("User", "email", request.getEmail()));

    account.setPassword(passwordEncoder.encode(request.getPassword().trim()));
    account.setUpdatedAt(Instant.now());
    userAccountRepository.save(account);

    log.info("Password changed successfully for user: {}", account.getUserId());
    return new ChangePasswordResponse("Password has been changed successfully.");
  }
}

