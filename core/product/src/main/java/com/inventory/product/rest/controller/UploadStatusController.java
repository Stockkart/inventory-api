package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.constants.ErrorCode;
import com.inventory.product.domain.model.ParsedInventoryResult;
import com.inventory.product.domain.model.UploadToken;
import com.inventory.product.domain.repository.ParsedInventoryResultRepository;
import com.inventory.product.rest.dto.inventory.ParsedInventoryListResponse;
import com.inventory.product.rest.dto.upload.UploadStatusResponse;
import com.inventory.product.service.UploadTokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/upload")
@Slf4j
public class UploadStatusController {

  @Autowired
  private UploadTokenService uploadTokenService;

  @Autowired
  private ParsedInventoryResultRepository parsedInventoryResultRepository;

  /**
   * Get upload status by token.
   * Desktop polls this endpoint to check if mobile upload is complete.
   *
   * @param token the upload token
   * @param httpRequest HTTP request containing userId from authentication
   * @return upload status response
   */
  @GetMapping("/status")
  public ResponseEntity<ApiResponse<UploadStatusResponse>> getUploadStatus(
      @RequestParam("token") String token,
      HttpServletRequest httpRequest) {
    
    // Get userId from request attributes (set by AuthenticationInterceptor)
    String userId = (String) httpRequest.getAttribute("userId");

    if (StringUtils.isEmpty(userId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated");
    }

    try {
      // Get token status
      UploadToken uploadToken = uploadTokenService.getTokenStatus(token);

      // Verify token belongs to the authenticated user
      if (!uploadToken.getUserId().equals(userId)) {
        throw new AuthenticationException(
            ErrorCode.UNAUTHORIZED,
            "Token does not belong to authenticated user");
      }

      UploadStatusResponse response = new UploadStatusResponse();
      response.setToken(token);
      response.setStatus(uploadToken.getStatus());
      response.setParsedInventoryId(uploadToken.getParsedInventoryId());
      
      if (uploadToken.getStatus() == UploadToken.UploadStatus.FAILED) {
        response.setErrorMessage("Upload or processing failed");
      }

      return ResponseEntity.ok(ApiResponse.success(response));
      
    } catch (Exception e) {
      log.error("Error getting upload status for token: {}", token, e);
      UploadStatusResponse errorResponse = new UploadStatusResponse();
      errorResponse.setToken(token);
      errorResponse.setStatus(UploadToken.UploadStatus.FAILED);
      errorResponse.setErrorMessage(e.getMessage());
      return ResponseEntity.ok(ApiResponse.success(errorResponse));
    }
  }

  /**
   * Get parsed inventory items for a completed upload.
   * Desktop calls this after status is COMPLETED to get the parsed items.
   *
   * @param token the upload token
   * @param httpRequest HTTP request containing userId from authentication
   * @return parsed inventory items
   */
  @GetMapping("/parsed-items")
  public ResponseEntity<ApiResponse<ParsedInventoryListResponse>> getParsedItems(
      @RequestParam("token") String token,
      HttpServletRequest httpRequest) {
    
    // Get userId from request attributes (set by AuthenticationInterceptor)
    String userId = (String) httpRequest.getAttribute("userId");

    if (StringUtils.isEmpty(userId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated");
    }

    // Get token status
    UploadToken uploadToken = uploadTokenService.getTokenStatus(token);

    // Verify token belongs to the authenticated user
    if (!uploadToken.getUserId().equals(userId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Token does not belong to authenticated user");
    }

    // Check if status is COMPLETED
    if (uploadToken.getStatus() != UploadToken.UploadStatus.COMPLETED) {
      throw new ResourceNotFoundException("Upload not completed yet. Current status: " + uploadToken.getStatus());
    }

    // Get parsed result
    ParsedInventoryResult result = parsedInventoryResultRepository.findByUploadTokenId(uploadToken.getId())
        .orElseThrow(() -> new ResourceNotFoundException("Parsed inventory result not found"));

    // Convert to response
    ParsedInventoryListResponse response = new ParsedInventoryListResponse();
    response.setItems(result.getParsedItems());
    response.setTotalItems(result.getParsedItems() != null ? result.getParsedItems().size() : 0);

    // Delete parsed result after retrieval since it's no longer needed
    // Desktop will create inventory from these items, so we don't need to keep the parsed result
    parsedInventoryResultRepository.delete(result);
    log.info("Deleted parsed inventory result {} after successful retrieval for token: {}", 
        result.getId(), token);

    return ResponseEntity.ok(ApiResponse.success(response));
  }
}
