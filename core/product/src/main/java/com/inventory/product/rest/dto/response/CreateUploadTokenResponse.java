package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateUploadTokenResponse {
  private String token;
  private String uploadUrl; // Full URL for QR code
  private long expiresInSeconds;
}
