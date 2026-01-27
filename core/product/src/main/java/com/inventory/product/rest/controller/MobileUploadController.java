package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.ParsedInventoryResult;
import com.inventory.product.domain.model.UploadToken;
import com.inventory.product.domain.repository.ParsedInventoryResultRepository;
import com.inventory.product.rest.dto.inventory.ParsedInventoryListResponse;
import com.inventory.product.rest.dto.upload.TokenValidationResponse;
import com.inventory.product.service.InventoryService;
import com.inventory.product.service.UploadTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/m")
@Slf4j
public class MobileUploadController {

  @Autowired
  private UploadTokenService uploadTokenService;

  @Autowired
  private InventoryService inventoryService;

  @Autowired
  private ParsedInventoryResultRepository parsedInventoryResultRepository;

  /**
   * Validate upload token - called by frontend to check if token is valid.
   * This is a public endpoint (no authentication required).
   *
   * @param token the upload token from QR code
   * @return token validation response
   */
  @GetMapping(value = "/upload/validate")
  public ResponseEntity<ApiResponse<TokenValidationResponse>> validateToken(
      @RequestParam("token") String token) {
    // Try to get token without validation first to get its status
    UploadToken uploadToken = uploadTokenService.getTokenWithoutValidation(token);
    
    TokenValidationResponse response = new TokenValidationResponse();
    response.setToken(token);
    
    if (uploadToken == null) {
      // Token doesn't exist
      response.setStatus(UploadToken.UploadStatus.EXPIRED);
      response.setExpiresAt(null);
      response.setErrorMessage("Invalid upload token");
      return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    // Check if expired
    if (uploadToken.getExpiresAt().isBefore(Instant.now())) {
      // Update status to EXPIRED if not already set
      if (uploadToken.getStatus() != UploadToken.UploadStatus.EXPIRED) {
        uploadTokenService.updateTokenStatus(token, UploadToken.UploadStatus.EXPIRED);
        uploadToken.setStatus(UploadToken.UploadStatus.EXPIRED);
      }
    }
    
    // Return token status regardless of expiry (frontend can decide what to do)
    response.setStatus(uploadToken.getStatus());
    response.setExpiresAt(uploadToken.getExpiresAt());
    
    // Set error message if token is expired or invalid
    if (uploadToken.getExpiresAt().isBefore(Instant.now())) {
      response.setErrorMessage("Upload token has expired");
    } else if (uploadToken.getStatus() == UploadToken.UploadStatus.FAILED) {
      response.setErrorMessage("Upload or processing failed");
    } else {
      response.setErrorMessage(null);
    }
    
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /**
   * Mobile upload endpoint - handles image upload from mobile device.
   * This is a public endpoint (no authentication required).
   *
   * @param token the upload token
   * @param image the image file to upload
   * @return success or error response
   */
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<String>> uploadImage(
      @RequestParam("token") String token,
      @RequestParam("image") MultipartFile image) {
    
    log.info("Received mobile upload request for token: {}, file: {}, size: {} bytes",
        token, image.getOriginalFilename(), image.getSize());

    try {
      // Validate token
      UploadToken uploadToken = uploadTokenService.validateToken(token);
      
      // Update status to UPLOADING
      uploadTokenService.updateTokenStatus(token, UploadToken.UploadStatus.UPLOADING);

      // Validate image
      if (image.isEmpty()) {
        uploadTokenService.markTokenFailed(token, "Image file is empty");
        return ResponseEntity.badRequest()
            .body(ApiResponse.error("Image file is empty"));
      }

      String contentType = image.getContentType();
      if (contentType == null || !contentType.startsWith("image/")) {
        uploadTokenService.markTokenFailed(token, "File must be an image");
        return ResponseEntity.badRequest()
            .body(ApiResponse.error("File must be an image"));
      }

      // Update status to PROCESSING
      uploadTokenService.updateTokenStatus(token, UploadToken.UploadStatus.PROCESSING);

      // Process image asynchronously
      CompletableFuture.runAsync(() -> {
        try {
          log.info("Processing uploaded image for token: {}", token);
          
          // Parse invoice image using existing service
          ParsedInventoryListResponse parsedResult = inventoryService.parseInvoiceImage(image);
          
          // Store parsed result
          ParsedInventoryResult result = new ParsedInventoryResult();
          result.setUploadTokenId(uploadToken.getId());
          result.setUserId(uploadToken.getUserId());
          result.setShopId(uploadToken.getShopId());
          result.setParsedItems(parsedResult.getItems());
          result.setCreatedAt(Instant.now());
          
          result = parsedInventoryResultRepository.save(result);
          
          // Mark token as completed with parsed result ID
          uploadTokenService.markTokenCompleted(token, result.getId());
          
          log.info("Successfully processed image for token: {}, parsed {} items, result ID: {}", 
              token, parsedResult.getTotalItems(), result.getId());
        } catch (Exception e) {
          log.error("Error processing image for token: {}", token, e);
          uploadTokenService.markTokenFailed(token, e.getMessage());
        }
      });

      return ResponseEntity.ok(ApiResponse.success("Image uploaded successfully. Processing..."));
      
    } catch (ValidationException e) {
      log.warn("Invalid token for upload: {}", token);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ApiResponse.error(e.getMessage()));
    } catch (Exception e) {
      log.error("Error handling upload for token: {}", token, e);
      uploadTokenService.markTokenFailed(token, e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(ApiResponse.error("Error processing upload: " + e.getMessage()));
    }
  }
}
