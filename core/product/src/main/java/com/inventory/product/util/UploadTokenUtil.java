package com.inventory.product.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Utility class for upload token operations.
 * Handles token generation, URL generation, and expiry calculations.
 */
@Component
public class UploadTokenUtil {

  @Value("${upload.token.expiry.minutes}")
  private int tokenExpiryMinutes;

  @Value("${client.url}")
  private String baseUrl;

  /**
   * Generate a unique token string.
   * 
   * @return a UUID-based token string
   */
  public String generateToken() {
    return UUID.randomUUID().toString();
  }

  /**
   * Calculate token expiry time based on configured expiry minutes.
   * 
   * @return the expiry instant
   */
  public Instant calculateExpiryTime() {
    return Instant.now().plusSeconds(tokenExpiryMinutes * 60L);
  }

  /**
   * Generate QR code upload URL for the given token.
   * 
   * @param token the upload token
   * @return the full URL for mobile upload
   */
  public String generateUploadUrl(String token) {
    return baseUrl + "/m/upload?token=" + token;
  }

  /**
   * Check if a token has expired.
   * 
   * @param expiresAt the expiry time
   * @return true if expired, false otherwise
   */
  public boolean isExpired(Instant expiresAt) {
    if (expiresAt == null) {
      return true;
    }
    return expiresAt.isBefore(Instant.now());
  }

  /**
   * Get token from repository without validation.
   * Utility method for checking token status even if expired.
   * 
   * @param token the token string
   * @param repository the upload token repository
   * @return the upload token if found, null otherwise
   */
  public com.inventory.product.domain.model.UploadToken getTokenWithoutValidation(
      String token,
      com.inventory.product.domain.repository.UploadTokenRepository repository) {
    if (token == null || token.trim().isEmpty()) {
      return null;
    }
    return repository.findByToken(token).orElse(null);
  }
}
