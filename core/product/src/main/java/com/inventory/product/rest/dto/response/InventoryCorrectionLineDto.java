package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.enums.InventoryCorrectionLineStatus;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryCorrectionLineDto {
  private String lineId;
  private String inventoryId;
  private String productName;
  private BigDecimal previousCurrentCount;
  private Integer previousCurrentBaseCount;
  private BigDecimal requestedCurrentCount;
  private Integer requestedCurrentBaseCount;
  private InventoryCorrectionLineStatus status;
  private Instant processedAt;
  private String processedByUserId;
  private String rejectionReason;
}

