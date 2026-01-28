package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.ParsedInventoryResult;
import com.inventory.product.domain.model.UploadToken;
import com.inventory.product.domain.repository.ParsedInventoryResultRepository;
import com.inventory.product.domain.repository.UploadTokenRepository;
import com.inventory.product.rest.dto.inventory.ParsedInventoryListResponse;
import com.inventory.product.rest.dto.upload.CreateUploadTokenResponse;
import com.inventory.product.rest.dto.upload.TokenValidationResponse;
import com.inventory.product.rest.dto.upload.UploadStatusResponse;
import com.inventory.product.rest.mapper.UploadTokenMapper;
import com.inventory.product.util.ParsedInventoryUtil;
import com.inventory.product.util.UploadTokenUtil;
import com.inventory.product.validation.UploadTokenValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@Transactional
public class QRUploadService {

  @Autowired
  private UploadTokenRepository uploadTokenRepository;

  @Autowired
  private ParsedInventoryResultRepository parsedInventoryResultRepository;

  @Autowired
  private InventoryService inventoryService;

  @Autowired
  private UploadTokenValidator uploadTokenValidator;

  @Autowired
  private UploadTokenMapper uploadTokenMapper;

  @Autowired
  private UploadTokenUtil uploadTokenUtil;

  @Autowired
  private ParsedInventoryUtil parsedInventoryUtil;

  /**
   * Create upload token for QR code pairing.
   * 
   * @param userId the user ID
   * @param shopId the shop ID
   * @return create upload token response
   */
  public CreateUploadTokenResponse createUploadToken(String userId, String shopId) {
    uploadTokenValidator.validateUserIdAndShopId(userId, shopId);

    log.info("Creating upload token for user: {}, shop: {}", userId, shopId);

    String token = uploadTokenUtil.generateToken();
    Instant expiresAt = uploadTokenUtil.calculateExpiryTime();

    UploadToken uploadToken = new UploadToken();
    uploadToken.setToken(token);
    uploadToken.setUserId(userId);
    uploadToken.setShopId(shopId);
    uploadToken.setExpiresAt(expiresAt);
    uploadToken.setCreatedAt(Instant.now());
    uploadToken.setStatus(UploadToken.UploadStatus.PENDING);

    uploadToken = uploadTokenRepository.save(uploadToken);
    String uploadUrl = uploadTokenUtil.generateUploadUrl(token);

    CreateUploadTokenResponse response = uploadTokenMapper.toCreateUploadTokenResponse(uploadToken, uploadUrl);
    log.info("Created upload token: {} with URL: {}", token, uploadUrl);

    return response;
  }

  /**
   * Validate upload token and return validation response.
   * 
   * @param token the token to validate
   * @return token validation response
   */
  @Transactional(readOnly = true)
  public TokenValidationResponse validateToken(String token) {
    uploadTokenValidator.validateToken(token);

    UploadToken uploadToken = uploadTokenUtil.getTokenWithoutValidation(token, uploadTokenRepository);

    TokenValidationResponse response = new TokenValidationResponse();
    response.setToken(token);

    if (uploadToken == null) {
      response.setStatus(UploadToken.UploadStatus.EXPIRED);
      response.setExpiresAt(null);
      response.setErrorMessage("Invalid upload token");
      return response;
    }

    // Check if expired and update status if needed
    if (uploadTokenUtil.isExpired(uploadToken.getExpiresAt())) {
      if (uploadToken.getStatus() != UploadToken.UploadStatus.EXPIRED) {
        updateTokenStatus(token, UploadToken.UploadStatus.EXPIRED);
        uploadToken.setStatus(UploadToken.UploadStatus.EXPIRED);
      }
    }

    // Map to response
    response = uploadTokenMapper.toTokenValidationResponse(uploadToken);

    // Set error message based on status
    if (uploadTokenUtil.isExpired(uploadToken.getExpiresAt())) {
      response.setErrorMessage("Upload token has expired");
    } else if (uploadToken.getStatus() == UploadToken.UploadStatus.FAILED) {
      response.setErrorMessage("Upload or processing failed");
    } else {
      response.setErrorMessage(null);
    }

    return response;
  }

  /**
   * Upload and process image asynchronously.
   * 
   * @param token the upload token
   * @param image the image file
   */
  public void uploadAndProcessImage(String token, MultipartFile image) {
    // Validate token
    UploadToken uploadToken = validateAndGetToken(token);

    // Validate image
    uploadTokenValidator.validateImageFile(image);

    // Read image bytes synchronously in the request thread. MultipartFile uses temp files
    // that are deleted after the request completes; passing MultipartFile to async task
    // causes "Error reading image file: /tmp/...upload_xxx.tmp" in production.
    byte[] imageBytes;
    try {
      imageBytes = image.getBytes();
      if (imageBytes == null || imageBytes.length == 0) {
        markTokenFailed(token, "Image file is empty");
        throw new ValidationException("Image file is empty");
      }
    } catch (Exception e) {
      log.error("Error reading image file for token: {}", token, e);
      markTokenFailed(token, "Error reading image file: " + e.getMessage());
      throw new ValidationException("Error reading image file: " + e.getMessage());
    }

    // Update status to UPLOADING then PROCESSING
    updateTokenStatus(token, UploadToken.UploadStatus.UPLOADING);
    updateTokenStatus(token, UploadToken.UploadStatus.PROCESSING);

    final byte[] finalImageBytes = imageBytes;

    // Process image asynchronously using bytes only (no MultipartFile)
    CompletableFuture.runAsync(() -> {
      try {
        log.info("Processing uploaded image for token: {}", token);

        ParsedInventoryListResponse parsedResult =
            inventoryService.parseInvoiceImageFromBytes(finalImageBytes);

        ParsedInventoryResult result = parsedInventoryUtil.createParsedInventoryResult(
            uploadToken.getId(),
            uploadToken.getUserId(),
            uploadToken.getShopId(),
            parsedResult.getItems());

        result = parsedInventoryResultRepository.save(result);
        markTokenCompleted(token, result.getId());

        log.info("Successfully processed image for token: {}, parsed {} items, result ID: {}",
            token, parsedResult.getTotalItems(), result.getId());
      } catch (Exception e) {
        log.error("Error processing image for token: {}", token, e);
        markTokenFailed(token, e.getMessage());
      }
    });
  }

  /**
   * Get upload status for a token.
   * 
   * @param token the token
   * @param userId the user ID for ownership validation
   * @return upload status response
   */
  @Transactional(readOnly = true)
  public UploadStatusResponse getUploadStatus(String token, String userId) {
    UploadToken uploadToken = getTokenStatus(token);
    uploadTokenValidator.validateTokenOwnership(uploadToken, userId);

    UploadStatusResponse response = uploadTokenMapper.toUploadStatusResponse(uploadToken);

    if (uploadToken.getStatus() == UploadToken.UploadStatus.FAILED) {
      response.setErrorMessage("Upload or processing failed");
    }

    return response;
  }

  /**
   * Get parsed inventory items for a completed upload.
   * 
   * @param token the token
   * @param userId the user ID for ownership validation
   * @return parsed inventory list response
   */
  @Transactional
  public ParsedInventoryListResponse getParsedItems(String token, String userId) {
    UploadToken uploadToken = getTokenStatus(token);
    uploadTokenValidator.validateTokenOwnership(uploadToken, userId);
    uploadTokenValidator.validateTokenStatus(uploadToken, UploadToken.UploadStatus.COMPLETED);

    // Get parsed result
    ParsedInventoryResult result = parsedInventoryResultRepository.findByUploadTokenId(uploadToken.getId())
        .orElseThrow(() -> new ResourceNotFoundException("Parsed inventory result not found"));

    // Convert to response using util
    ParsedInventoryListResponse response = parsedInventoryUtil.toResponse(result);

    // Delete parsed result after retrieval since it's no longer needed
    parsedInventoryResultRepository.delete(result);
    log.info("Deleted parsed inventory result {} after successful retrieval for token: {}",
        result.getId(), token);

    return response;
  }

  // ========== Token Repository Operations ==========

  /**
   * Validate and get upload token.
   * 
   * @param token the token to validate
   * @return the upload token if valid
   * @throws ValidationException if token is invalid or expired
   */
  @Transactional(readOnly = true)
  public UploadToken validateAndGetToken(String token) {
    if (token == null || token.trim().isEmpty()) {
      throw new ValidationException("Upload token is required");
    }

    UploadToken uploadToken = uploadTokenRepository.findByToken(token)
        .orElseThrow(() -> new ValidationException("Invalid upload token"));

    // Check if expired
    if (uploadTokenUtil.isExpired(uploadToken.getExpiresAt())) {
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
    return validateAndGetToken(token);
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
