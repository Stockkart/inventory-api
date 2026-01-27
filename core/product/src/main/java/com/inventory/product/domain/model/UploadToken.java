package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "upload_tokens")
public class UploadToken {
  
  @Id
  private String id;
  private String token;
  private String userId;
  private String shopId;
  private Instant expiresAt;
  private Instant createdAt;
  private UploadStatus status;
  private String uploadedImageId; // Reference to uploaded image/document
  private String parsedInventoryId; // Reference to parsed inventory result
  
  public enum UploadStatus {
    PENDING,      // Token created, waiting for upload
    UPLOADING,    // Upload in progress
    PROCESSING,   // Image uploaded, OCR processing
    COMPLETED,    // Processing complete, inventory parsed
    FAILED,       // Upload or processing failed
    EXPIRED       // Token expired
  }
}
