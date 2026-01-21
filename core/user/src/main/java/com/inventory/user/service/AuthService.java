package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.rest.dto.auth.LoginRequest;
import com.inventory.user.rest.dto.auth.LoginResponse;
import com.inventory.user.rest.dto.auth.LogoutResponse;
import com.inventory.user.rest.dto.auth.SignupRequest;
import com.inventory.user.rest.dto.auth.SignupResponse;
import com.inventory.user.rest.dto.auth.UserResponse;
import com.inventory.user.rest.mapper.UserMapper;
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
  private FacebookTokenVerifier facebookTokenVerifier;
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

      // Check if OAuth ID token is provided
      if (request.getIdToken() != null && !request.getIdToken().trim().isEmpty()) {
        // OAuth authentication (Google/Facebook)
        String loginType = request.getLoginType() != null ? request.getLoginType().toLowerCase() : null;
        
        if (loginType == null) {
          throw new ValidationException("loginType is required when idToken is provided");
        }

        String email;
        Map<String, Object> payload;

        if ("google".equals(loginType)) {
          // Google authentication
          log.debug("Attempting Google login");

          // Verify Google ID token
          payload = googleTokenVerifier.verifyToken(request.getIdToken());
          email = googleTokenVerifier.getEmail(payload);

          log.debug("Google login for email: {}", email);
        } else if ("facebook".equals(loginType)) {
          // Facebook authentication
          log.debug("Attempting Facebook login");

          // Verify Facebook access token
          payload = facebookTokenVerifier.verifyToken(request.getIdToken());
          email = facebookTokenVerifier.getEmail(payload);

          log.debug("Facebook login for email: {}", email);
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
          LoginResponse.ShopInfo shopInfo = new LoginResponse.ShopInfo();
          shopInfo.setSgst(taxInfo.getSgst());
          shopInfo.setCgst(taxInfo.getCgst());
          shopInfo.setName(taxInfo.getName());
          response.setShop(shopInfo);
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

      // Check if OAuth ID token is provided
      if (request.getIdToken() != null && !request.getIdToken().trim().isEmpty()) {
        // OAuth signup (Google/Facebook)
        String signupType = request.getSignupType() != null ? request.getSignupType().toLowerCase() : null;
        
        if (signupType == null) {
          throw new ValidationException("signupType is required when idToken is provided");
        }

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
        } else if ("facebook".equals(signupType)) {
          // Facebook signup
          log.debug("Attempting Facebook signup");

          // Verify Facebook access token
          payload = facebookTokenVerifier.verifyToken(request.getIdToken());
          email = facebookTokenVerifier.getEmail(payload);
          name = facebookTokenVerifier.getName(payload);

          log.debug("Facebook signup for email: {}", email);
        } else {
          throw new ValidationException("Invalid signupType. Must be 'google' or 'facebook'");
        }

        // Check if user already exists
        if (userAccountRepository.findByEmail(email).isPresent()) {
          String providerName = signupType.substring(0, 1).toUpperCase() + signupType.substring(1);
          log.warn("{} signup attempt with existing email: {}", providerName, email);
          throw new ValidationException("User with this email already exists. Please login instead.");
        }

        // Create user account from OAuth data
        // MongoDB will auto-generate the userId as ObjectId
        account = new UserAccount();
        account.setEmail(email);
        account.setName(name != null ? name : email.split("@")[0]); // Use email prefix if name not available
        account.setRole(request.getRole() != null ? request.getRole() : UserRole.OWNER);
        account.setShopId(request.getShopId());
        account.setActive(true);
        account.setInviteAccepted(false);
        Instant now = Instant.now();
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        // No password for OAuth-authenticated users
        account.setPassword(null);

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
      // Validate inputs
      if (userId == null || userId.trim().isEmpty()) {
        throw new ValidationException("User ID is required");
      }
      if (accessToken == null || accessToken.trim().isEmpty()) {
        throw new ValidationException("Access token is required");
      }

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

  @Transactional(readOnly = true)
  public UserResponse getCurrentUser(String accessToken) {
    try {
      log.debug("Getting current user for token");

      // Validate token and get user account
      UserAccount account = tokenValidationService.validateToken(accessToken);

      log.info("Retrieved current user: {}", account.getUserId());

      // Map to response using mapper
      return userMapper.toUserResponse(account);

    } catch (AuthenticationException e) {
      log.warn("Failed to get current user: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting current user: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving user information");
    } catch (Exception e) {
      log.error("Unexpected error while getting current user: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }
}

