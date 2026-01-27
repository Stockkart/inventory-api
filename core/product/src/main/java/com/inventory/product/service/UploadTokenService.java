package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.UploadToken;
import com.inventory.product.domain.repository.UploadTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@Transactional
public class UploadTokenService {

  @Autowired
  private UploadTokenRepository uploadTokenRepository;

  @Value("${upload.token.expiry.minutes}")
  private int tokenExpiryMinutes = 5;

  @Value("${client.url}")
  private String baseUrl;

  /**
   * Create a new upload token for the authenticated user.
   * 
   * @param userId the user ID
   * @param shopId the shop ID
   * @return the created upload token
   */
  public UploadToken createToken(String userId, String shopId) {
    log.info("Creating upload token for user: {}, shop: {}", userId, shopId);

    String token = UUID.randomUUID().toString();
    Instant expiresAt = Instant.now().plusSeconds(tokenExpiryMinutes * 60L);

    UploadToken uploadToken = new UploadToken();
    uploadToken.setToken(token);
    uploadToken.setUserId(userId);
    uploadToken.setShopId(shopId);
    uploadToken.setExpiresAt(expiresAt);
    uploadToken.setCreatedAt(Instant.now());
    uploadToken.setStatus(UploadToken.UploadStatus.PENDING);

    uploadToken = uploadTokenRepository.save(uploadToken);
    log.info("Created upload token: {} for user: {}", token, userId);

    return uploadToken;
  }

  /**
   * Validate and get upload token.
   * 
   * @param token the token to validate
   * @return the upload token if valid
   * @throws ValidationException if token is invalid or expired
   */
  @Transactional(readOnly = true)
  public UploadToken validateToken(String token) {
    if (token == null || token.trim().isEmpty()) {
      throw new ValidationException("Upload token is required");
    }

    UploadToken uploadToken = uploadTokenRepository.findByToken(token)
        .orElseThrow(() -> new ValidationException("Invalid upload token"));

    // Check if expired
    if (uploadToken.getExpiresAt().isBefore(Instant.now())) {
      uploadToken.setStatus(UploadToken.UploadStatus.EXPIRED);
      uploadTokenRepository.save(uploadToken);
      throw new ValidationException("Upload token has expired");
    }

    return uploadToken;
  }

  /**
   * Update token status.
   * 
   * @param token the token
   * @param status the new status
   */
  public void updateTokenStatus(String token, UploadToken.UploadStatus status) {
    UploadToken uploadToken = uploadTokenRepository.findByToken(token)
        .orElseThrow(() -> new ResourceNotFoundException("Upload token not found"));

    uploadToken.setStatus(status);
    uploadTokenRepository.save(uploadToken);
    log.info("Updated token {} status to {}", token, status);
  }

  /**
   * Mark token as completed with parsed inventory result.
   * 
   * @param token the token
   * @param parsedInventoryId the parsed inventory result ID
   */
  public void markTokenCompleted(String token, String parsedInventoryId) {
    UploadToken uploadToken = uploadTokenRepository.findByToken(token)
        .orElseThrow(() -> new ResourceNotFoundException("Upload token not found"));

    uploadToken.setStatus(UploadToken.UploadStatus.COMPLETED);
    uploadToken.setParsedInventoryId(parsedInventoryId);
    uploadTokenRepository.save(uploadToken);
    log.info("Marked token {} as completed with parsed inventory: {}", token, parsedInventoryId);
  }

  /**
   * Get parsed inventory result ID for a token.
   * 
   * @param token the token
   * @return the parsed inventory result ID
   */
  @Transactional(readOnly = true)
  public String getParsedInventoryId(String token) {
    UploadToken uploadToken = uploadTokenRepository.findByToken(token)
        .orElseThrow(() -> new ResourceNotFoundException("Upload token not found"));
    return uploadToken.getParsedInventoryId();
  }

  /**
   * Mark token as failed.
   * 
   * @param token the token
   * @param errorMessage optional error message
   */
  public void markTokenFailed(String token, String errorMessage) {
    UploadToken uploadToken = uploadTokenRepository.findByToken(token)
        .orElseThrow(() -> new ResourceNotFoundException("Upload token not found"));

    uploadToken.setStatus(UploadToken.UploadStatus.FAILED);
    uploadTokenRepository.save(uploadToken);
    log.warn("Marked token {} as failed: {}", token, errorMessage);
  }

  /**
   * Get upload status by token.
   * 
   * @param token the token
   * @return the upload token with current status
   */
  @Transactional(readOnly = true)
  public UploadToken getTokenStatus(String token) {
    return validateToken(token);
  }

  /**
   * Get token without validation (for status checking even if expired).
   * 
   * @param token the token
   * @return the upload token if found, null otherwise
   */
  @Transactional(readOnly = true)
  public UploadToken getTokenWithoutValidation(String token) {
    if (token == null || token.trim().isEmpty()) {
      return null;
    }
    return uploadTokenRepository.findByToken(token).orElse(null);
  }

  /**
   * Generate QR code URL for the token.
   * 
   * @param token the token
   * @return the full URL for mobile upload
   */
  public String generateUploadUrl(String token) {
    return baseUrl + "/m/upload?token=" + token;
  }

  /**
   * Clean up expired tokens (runs every hour).
   */
  @Scheduled(fixedRate = 3600000) // 1 hour
  @Transactional
  public void cleanupExpiredTokens() {
    log.info("Cleaning up expired upload tokens");
    uploadTokenRepository.deleteByExpiresAtBefore(Instant.now());
  }
}
