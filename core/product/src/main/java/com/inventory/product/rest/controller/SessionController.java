package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.constants.ErrorCode;
import com.inventory.product.domain.model.UploadToken;
import com.inventory.product.rest.dto.upload.CreateUploadTokenResponse;
import com.inventory.product.service.UploadTokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/session")
@Slf4j
public class SessionController {

  @Autowired
  private UploadTokenService uploadTokenService;

  /**
   * Create an upload token for QR code pairing.
   * Desktop calls this to generate a token that mobile can use to upload images.
   *
   * @param httpRequest HTTP request containing userId and shopId from authentication
   * @return upload token response with QR code URL
   */
  @PostMapping("/create-upload-token")
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

    log.info("Creating upload token for user: {}, shop: {}", userId, shopId);

    // Create token
    UploadToken token = uploadTokenService.createToken(userId, shopId);

    // Generate upload URL for QR code
    String uploadUrl = uploadTokenService.generateUploadUrl(token.getToken());

    // Calculate expiry in seconds
    long expiresInSeconds = Duration.between(Instant.now(), token.getExpiresAt()).getSeconds();

    CreateUploadTokenResponse response = new CreateUploadTokenResponse();
    response.setToken(token.getToken());
    response.setUploadUrl(uploadUrl);
    response.setExpiresInSeconds(expiresInSeconds);

    log.info("Created upload token: {} with URL: {}", token.getToken(), uploadUrl);

    return ResponseEntity.ok(ApiResponse.success(response));
  }
}
