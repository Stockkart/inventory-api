package com.inventory.product.rest.dto.request;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateInventoryCorrectionRequest {
  private String vendorPurchaseInvoiceId;
  private String invoiceNo;
  private String vendorId;
  private String vendorName;
  private String note;
  private List<InventoryCorrectionLineRequest> lines;
}

