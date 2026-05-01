package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.enums.InventoryCorrectionStatus;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryCorrectionDto {
  private String id;
  private String vendorPurchaseInvoiceId;
  private String invoiceNo;
  private String vendorId;
  private String vendorName;
  private InventoryCorrectionStatus status;
  private String note;
  private Instant createdAt;
  private String createdByUserId;
  private Instant updatedAt;
  private List<InventoryCorrectionLineDto> lines;
}

