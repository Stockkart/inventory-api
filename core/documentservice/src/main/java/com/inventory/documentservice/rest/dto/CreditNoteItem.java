package com.inventory.documentservice.rest.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Line item for credit-note PDF generation.
 */
@Data
public class CreditNoteItem {
  private BigDecimal quantity;
  private String name;
  private String hsn;
  private String companyName;
  private String batchNo;
  private BigDecimal unitPrice;
  private BigDecimal taxableValue;
  private BigDecimal cgstAmount;
  private BigDecimal sgstAmount;
  private BigDecimal lineTotal;
  private String cgst;
  private String sgst;
  /** Combined GST % for thermal display. */
  private BigDecimal gstPercent;
}
