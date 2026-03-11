package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.ValidationException;
import com.inventory.common.constants.ErrorCode;
import com.inventory.product.rest.dto.response.CreateUploadTokenResponse;
import com.inventory.product.rest.dto.response.ParsedInventoryListResponse;
import com.inventory.product.rest.dto.response.TokenValidationResponse;
import com.inventory.product.rest.dto.response.UploadStatusResponse;
import com.inventory.product.service.QRUploadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Latency(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class QRUploadController {

  @Autowired
  private QRUploadService qrUploadService;

  /**
   * Create an upload token for QR code pairing.
   * Desktop calls this to generate a token that mobile can use to upload images.
   * 
   * Path: POST /api/v1/session/create-upload-token
   *
   * @param httpRequest HTTP request containing userId and shopId from authentication
   * @return upload token response with QR code URL
   */
  @PostMapping("/api/v1/session/create-upload-token")
  public ResponseEntity<ApiResponse<CreateUploadTokenResponse>> createUploadToken(
      HttpServletRequest httpRequest) {
    // Get userId and shopId from request attributes (set by AuthenticationInterceptor)
    String userId = (String) httpRequest.getAttribute("userId");
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated or shop not found");
    }

    CreateUploadTokenResponse response = qrUploadService.createUploadToken(userId, shopId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /**
   * Validate upload token - called by frontend to check if token is valid.
   * This is a public endpoint (no authentication required).
   * 
   * Path: GET /m/upload/validate?token=...
   *
   * @param token the upload token from QR code
   * @return token validation response
   */
  @GetMapping("/m/upload/validate")
  public ResponseEntity<ApiResponse<TokenValidationResponse>> validateToken(
      @RequestParam("token") String token) {
    TokenValidationResponse response = qrUploadService.validateToken(token);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /**
   * Mobile upload endpoint - handles image upload from mobile device.
   * This is a public endpoint (no authentication required).
   * 
   * Path: POST /m/upload?token=...
   *
   * @param token the upload token
   * @param image the image file to upload
   * @return success or error response
   */
  @PostMapping(value = "/m/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<String>> uploadImage(
      @RequestParam("token") String token,
      @RequestParam("image") MultipartFile image) {

    log.info("Received mobile upload request for token: {}, file: {}, size: {} bytes",
        token, image.getOriginalFilename(), image.getSize());

    try {
      qrUploadService.uploadAndProcessImage(token, image);
      return ResponseEntity.ok(ApiResponse.success("Image uploaded successfully. Processing..."));
    } catch (ValidationException e) {
      log.warn("Invalid token for upload: {}", token);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ApiResponse.error(e.getMessage()));
    } catch (Exception e) {
      log.error("Error handling upload for token: {}", token, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(ApiResponse.error("Error processing upload: " + e.getMessage()));
    }
  }

  /**
   * Get upload status by token.
   * Desktop polls this endpoint to check if mobile upload is complete.
   * 
   * Path: GET /api/v1/upload/status?token=...
   *
   * @param token the upload token
   * @param httpRequest HTTP request containing userId from authentication
   * @return upload status response
   */
  @GetMapping("/api/v1/upload/status")
  public ResponseEntity<ApiResponse<UploadStatusResponse>> getUploadStatus(
      @RequestParam("token") String token,
      HttpServletRequest httpRequest) {

    String userId = (String) httpRequest.getAttribute("userId");

    if (StringUtils.isEmpty(userId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated");
    }

    UploadStatusResponse response = qrUploadService.getUploadStatus(token, userId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /**
   * Get parsed inventory items for a completed upload.
   * Desktop calls this after status is COMPLETED to get the parsed items.
   * 
   * Path: GET /api/v1/upload/parsed-items?token=...
   *
   * @param token the upload token
   * @param httpRequest HTTP request containing userId from authentication
   * @return parsed inventory items
   */
  @GetMapping("/api/v1/upload/parsed-items")
  public ResponseEntity<ApiResponse<ParsedInventoryListResponse>> getParsedItems(
      @RequestParam("token") String token,
      HttpServletRequest httpRequest) {

    String userId = (String) httpRequest.getAttribute("userId");

    if (StringUtils.isEmpty(userId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated");
    }

    ParsedInventoryListResponse response = qrUploadService.getParsedItems(token, userId);
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}
