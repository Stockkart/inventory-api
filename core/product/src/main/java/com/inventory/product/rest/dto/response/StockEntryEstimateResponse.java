package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.StockEntryEstimateLine;
import com.inventory.product.domain.model.enums.StockEntryEstimateState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockEntryEstimateResponse {
  private String id;
  private String estimateNo;
  private StockEntryEstimateState state;
  private String vendorId;
  private String vendorInvoiceNo;
  private Instant vendorInvoiceDate;
  private BigDecimal lineSubTotal;
  private BigDecimal taxTotal;
  private BigDecimal shippingCharge;
  private BigDecimal otherCharges;
  private BigDecimal overallDiscount;
  private BigDecimal roundOff;
  private BigDecimal invoiceTotal;
  private String paymentMethod;
  private BigDecimal cashAmount;
  private BigDecimal onlineAmount;
  private BigDecimal creditAmount;
  private BigDecimal paidAmount;
  private List<StockEntryEstimateLine> lines;
  private String vendorPurchaseInvoiceId;
  private String convertedToVendorPurchaseInvoiceId;
  private Instant lockedAt;
  private Instant createdAt;
  private Instant updatedAt;
}
