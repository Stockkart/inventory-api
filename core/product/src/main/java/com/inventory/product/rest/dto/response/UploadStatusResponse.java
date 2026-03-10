package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.UploadToken;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UploadStatusResponse {
  private String token;
  private UploadToken.UploadStatus status;
  private String parsedInventoryId; // ID of parsed inventory result when completed
  private String errorMessage; // Error message if status is FAILED
}
