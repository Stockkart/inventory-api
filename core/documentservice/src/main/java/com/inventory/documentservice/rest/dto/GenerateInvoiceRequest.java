package com.inventory.documentservice.rest.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Request DTO for invoice generation.
 * Contains all fields needed for invoice PDF generation.
 */
@Data
public class GenerateInvoiceRequest {
  // Invoice basic info
  private String invoiceNo;
  private String invoiceDate;
  private String invoiceTime;
  
  // Shop/Seller information
  private String shopName;
  private String shopAddress;
  private String shopDlNo;
  private String shopFssai;
  private String shopGstin;
  private String shopPhone;
  private String shopEmail;
  
  // Customer/Buyer information
  private String customerName;
  private String customerAddress;
  private String customerDlNo;
  private String customerGstin;
  private String customerPan;
  private String customerPhone;
  private String customerEmail;
  
  // Invoice items
  private List<InvoiceItem> items;
  
  // Totals and calculations
  private BigDecimal subTotal;
  private BigDecimal discountTotal;
  private BigDecimal sgstAmount;
  private BigDecimal cgstAmount;
  private BigDecimal sgstPercent;
  private BigDecimal cgstPercent;
  private BigDecimal taxTotal;
  private BigDecimal roundOff;
  private BigDecimal grandTotal;
  
  // Additional fields
  private String paymentMethod;
  private String amountInWords;
  private String footerNote;
  
  // Legacy fields (for backward compatibility)
  private Instant soldAt;
}
