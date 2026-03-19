package com.inventory.taxation.domain.gstr2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CDNUR - Credit/Debit notes received from unregistered suppliers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gstr2CdnurLine {

  private String noteNumber;
  private LocalDate noteDate;
  private String invoiceNo;
  private LocalDate invoiceDate;
  private String preGst;
  private String documentType;
  private String reasonForIssuing;
  private String supplyType;
  private String invoiceType;
  private BigDecimal noteValue;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal integratedTaxPaid;
  private BigDecimal centralTaxPaid;
  private BigDecimal stateUtTaxPaid;
  private BigDecimal cessPaid;
  private String itcEligibility;
  private BigDecimal availedItcIntegrated;
}
