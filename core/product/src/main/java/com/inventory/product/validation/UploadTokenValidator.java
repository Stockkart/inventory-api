package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.UploadToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;

@Component
public class UploadTokenValidator {

  public void validateToken(String token) {
    if (!StringUtils.hasText(token)) {
      throw new ValidationException("Upload token is required");
    }
  }

  public void validateTokenOwnership(UploadToken uploadToken, String userId) {
    if (uploadToken == null) {
      throw new ValidationException("Upload token not found");
    }
    if (!uploadToken.getUserId().equals(userId)) {
      throw new ValidationException("Token does not belong to authenticated user");
    }
  }

  public void validateTokenNotExpired(UploadToken uploadToken) {
    if (uploadToken == null) {
      throw new ValidationException("Upload token not found");
    }
    if (uploadToken.getExpiresAt().isBefore(Instant.now())) {
      throw new ValidationException("Upload token has expired");
    }
  }

  public void validateTokenStatus(UploadToken uploadToken, UploadToken.UploadStatus expectedStatus) {
    if (uploadToken == null) {
      throw new ValidationException("Upload token not found");
    }
    if (uploadToken.getStatus() != expectedStatus) {
      throw new ValidationException("Upload not completed yet. Current status: " + uploadToken.getStatus());
    }
  }

  public void validateImageFile(MultipartFile image) {
    if (image == null || image.isEmpty()) {
      throw new ValidationException("Image file is required");
    }

    String contentType = image.getContentType();
    if (contentType == null || !contentType.startsWith("image/")) {
      throw new ValidationException("File must be an image");
    }
  }

  public void validateUserIdAndShopId(String userId, String shopId) {
    if (!StringUtils.hasText(userId)) {
      throw new ValidationException("User ID is required");
    }
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }
  }
}
