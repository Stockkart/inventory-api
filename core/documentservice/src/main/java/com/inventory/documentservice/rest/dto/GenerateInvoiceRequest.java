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
  private String billingMode;
  /**
   * Printer layout: {@code NORMAL}, {@code DOT_MATRIX}, or {@code THERMAL_3INCH}.
   * Resolved via {@link com.inventory.documentservice.domain.PrinterType}.
   */
  private String printerType;
  private Boolean showSellerDetails;
  private Boolean showBuyerDetails;
  private Boolean showTaxDetails;
  /** When false, SCHEME column is hidden (e.g. for retailer shops). */
  private Boolean showScheme;
  private Boolean showPaymentMethod;
  private Boolean showAmountInWords;
  private Boolean showAmountSaved;
  private Boolean showAdditionalDiscount;
  private Boolean showHsn;
  private Boolean showMfg;
  private Boolean showExpiry;
  private Boolean showBatch;
  private Boolean showMrp;
  private Boolean showLineDiscount;
  private Boolean showSignatures;

  // Shop/Seller information
  private String shopName;
  private String shopAddress;
  private String shopDlNo;
  private String shopFssai;
  private String shopGstin;
  private String shopPhone;
  private String shopEmail;
  private String shopTagline; // Optional: Shop tagline, banner word, or highlight text
  /** Seller PAN (stored or derived from GSTIN). */
  private String shopPan;
  /** Place of supply (typically shop state) for tax invoices. */
  private String placeOfSupply;
  
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
  private BigDecimal saleAdditionalDiscountTotal;
  private BigDecimal sgstAmount;
  private BigDecimal cgstAmount;
  private BigDecimal sgstPercent;
  private BigDecimal cgstPercent;
  private BigDecimal taxTotal;
  private BigDecimal roundOff;
  private BigDecimal grandTotal;
  private BigDecimal totalMRPAmount; // Sum of all MRPs (maximumRetailPrice * quantity)
  private BigDecimal totalAmountSaved; // totalMRPAmount - grandTotal
  
  // Additional fields
  private String paymentMethod;
  private String amountInWords;
  private String footerNote;
  
  // Legacy fields (for backward compatibility)
  private Instant soldAt;
}
