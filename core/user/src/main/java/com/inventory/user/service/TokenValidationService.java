package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserToken;
import com.inventory.user.domain.repository.UserAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Slf4j
public class TokenValidationService {

  @Autowired
  private UserAccountRepository userAccountRepository;

  @Autowired(required = false)
  private AuthCache authCache;

  @Transactional(readOnly = true)
  public UserAccount validateToken(String accessToken) {
    if (accessToken == null || accessToken.trim().isEmpty()) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Access token is required");
    }

    // Recently seen token (≤ auth.cache.ttl-seconds): same checks, no lookup
    UserAccount account = authCache != null ? authCache.getAccount(accessToken) : null;
    boolean fromCache = account != null;

    if (account == null) {
      // Find user by access token
      account = userAccountRepository.findByAccessToken(accessToken)
          .orElseThrow(() -> new AuthenticationException(ErrorCode.INVALID_CREDENTIALS, "Invalid or expired token"));
    }

    // Check if account is active
    if (!account.isActive()) {
      log.warn("Attempted to access deactivated account: {}", account.getUserId());
      throw new AuthenticationException(ErrorCode.ACCOUNT_DISABLED, "Account is deactivated");
    }

    // Check if token is expired
    Instant now = Instant.now();
    UserToken token = account.getTokens().stream()
        .filter(t -> accessToken.equals(t.getAccessToken()))
        .filter(t -> t.getExpiresAt() == null || t.getExpiresAt().isAfter(now))
        .findFirst()
        .orElse(null);

    if (token == null) {
      log.warn("Attempted to use expired token for user: {}", account.getUserId());
      throw new AuthenticationException(ErrorCode.INVALID_CREDENTIALS, "Token has expired");
    }

    if (!fromCache && authCache != null) {
      authCache.putAccount(accessToken, account, token);
    }
    return account;
  }
}
