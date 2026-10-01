package com.inventory.product.rest.dto.request;

import com.inventory.product.domain.model.StockEntryEstimateLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.Data;

@Data
public class UpsertStockEntryEstimateRequest {
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
}
