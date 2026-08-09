package com.inventory.documentservice.rest.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTO for credit-note PDF generation (customer sales return or vendor purchase return).
 */
@Data
public class GenerateCreditNoteRequest {
  private String creditNoteNo;
  private String noteDate;
  private String noteTime;
  /** Original tax / purchase invoice this note is against. */
  private String againstInvoiceNo;
  /**
   * Printer layout: {@code NORMAL}, {@code DOT_MATRIX}, or {@code THERMAL_3INCH}.
   */
  private String printerType;

  /**
   * Who the other party is relative to the shop: {@code CUSTOMER} or {@code VENDOR}.
   * Affects labels (Bill To / Supplier).
   */
  private String partyRole;

  private Boolean showSellerDetails;
  private Boolean showBuyerDetails;
  private Boolean showTaxDetails;
  private Boolean showPaymentMethod;
  private Boolean showAmountInWords;
  private Boolean showHsn;
  private Boolean showMfg;
  private Boolean showBatch;
  private Boolean showSignatures;

  private String shopName;
  private String shopAddress;
  private String shopDlNo;
  private String shopFssai;
  private String shopGstin;
  private String shopPhone;
  private String shopEmail;
  private String shopTagline;
  private String shopPan;
  private String placeOfSupply;

  private String partyName;
  private String partyAddress;
  private String partyGstin;
  private String partyPan;
  private String partyPhone;
  private String partyEmail;
  private String partyDlNo;

  private List<CreditNoteItem> items;

  private BigDecimal taxableTotal;
  private BigDecimal sgstAmount;
  private BigDecimal cgstAmount;
  private BigDecimal sgstPercent;
  private BigDecimal cgstPercent;
  private BigDecimal taxTotal;
  private BigDecimal roundOff;
  private BigDecimal grandTotal;

  private String paymentMethod;
  private String amountInWords;
  private String footerNote;
  private String reason;
}
