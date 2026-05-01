package com.inventory.product.domain.model;

import com.inventory.product.domain.model.enums.InventoryCorrectionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "inventory_corrections")
public class InventoryCorrection {
  @Id
  private String id;
  private String shopId;
  private String vendorPurchaseInvoiceId;
  private String invoiceNo;
  private String vendorId;
  private String vendorName;
  private InventoryCorrectionStatus status;
  private String note;
  private Instant createdAt;
  private String createdByUserId;
  private Instant updatedAt;
  private List<InventoryCorrectionLine> lines = new ArrayList<>();
}

