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
  /** Resolved from {@link com.inventory.user.domain.model.Vendor}; null if missing or deleted */
  private String vendorName;
  private String invoiceNo;
  private Instant invoiceDate;
  private BigDecimal lineSubTotal;
  private BigDecimal taxTotal;
  private BigDecimal shippingCharge;
  private BigDecimal otherCharges;
  private BigDecimal overallDiscount;
  private BigDecimal roundOff;
  private BigDecimal invoiceTotal;
  private String paymentMethod;
  private BigDecimal paidAmount;
  private Instant createdAt;
  private Boolean synthetic;
  private String legacyLotId;
  private List<VendorPurchaseInvoiceLineDto> lines;

  /** Whether the line amounts on this bill already include GST. */
  private String taxTreatment;

  /** Set once the header has been corrected against the paper bill. */
  private Instant amendedAt;
  private String amendedByUserId;
  private String amendmentReason;
  /** The header as it stood before the last correction; null if never corrected. */
  private InvoiceHeaderFiguresDto previousHeader;
}
