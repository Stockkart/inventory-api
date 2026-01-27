package com.inventory.product.rest.dto.upload;

import com.inventory.product.domain.model.UploadToken;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenValidationResponse {
  private String token;
  private UploadToken.UploadStatus status;
  private Instant expiresAt;
  private String errorMessage;
}
