package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorPurchaseInvoiceDetailDto {
  private String id;
  private String vendorId;
  private String invoiceNo;
  private Instant invoiceDate;
  private BigDecimal lineSubTotal;
  private BigDecimal taxTotal;
  private BigDecimal shippingCharge;
  private BigDecimal otherCharges;
  private BigDecimal roundOff;
  private BigDecimal invoiceTotal;
  private Instant createdAt;
  private Boolean synthetic;
  private String legacyLotId;
  private List<VendorPurchaseInvoiceLineDto> lines;
}
