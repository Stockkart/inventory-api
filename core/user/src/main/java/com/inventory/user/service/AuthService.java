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
import com.inventory.user.rest.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.UUID;

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

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        try {
            // Input validation
            if (request == null) {
                throw new ValidationException("Login request cannot be null");
            }
            if (!StringUtils.hasText(request.getEmail())) {
                throw new ValidationException("Email is required");
            }
            if (!StringUtils.hasText(request.getPassword())) {
                throw new ValidationException("Password is required");
            }
            
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
            
            return LoginResponse.builder()
                    .accessToken(UUID.randomUUID().toString())
                    .refreshToken(UUID.randomUUID().toString())
                    .user(userMapper.toUserSummary(account))
                    .build();
                    
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
            // Input validation
            if (request == null) {
                throw new ValidationException("Request cannot be null");
            }
            if (!StringUtils.hasText(request.getInviteToken())) {
                throw new ValidationException("Invite token is required");
            }
            if (!StringUtils.hasText(request.getPassword())) {
                throw new ValidationException("Password is required");
            }
            if (request.getPassword().length() < 8) {
                throw new ValidationException("Password must be at least 8 characters long");
            }
            
            log.debug("Processing admin invite acceptance for token: {}", request.getInviteToken());
            
            // Find and validate invite
            UserInvite invite = userInviteRepository.findByToken(request.getInviteToken())
                    .orElseThrow(() -> new ResourceNotFoundException("Invite", "token", request.getInviteToken()));
                    
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
                    .orElseGet(() -> UserAccount.builder()
                            .userId("user-" + UUID.randomUUID())
                            .email(invite.getEmail())
                            .shopId(invite.getShopId())
                            .role(invite.getRole())
                            .active(true)
                            .inviteAccepted(true)
                            .build());
            
            // Update account details
            account.setName(invite.getName());
            account.setPassword(passwordEncoder.encode(request.getPassword()));
            account.setActive(true);
            account.setInviteAccepted(true);
            account.setUpdatedAt(Instant.now());
            
            userAccountRepository.save(account);
            
            log.info("Admin invite accepted successfully for user: {}", account.getUserId());
            
            return AcceptInviteResponse.builder()
                    .userId(account.getUserId())
                    .role(account.getRole())
                    .shopId(account.getShopId())
                    .active(account.isActive())
                    .build();
                    
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
}

