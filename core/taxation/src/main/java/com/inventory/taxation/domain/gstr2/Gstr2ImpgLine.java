package com.inventory.taxation.domain.gstr2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * IMPG - Import of goods (Bill of Entry).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gstr2ImpgLine {

  private String portCode;
  private String billOfEntryNo;
  private LocalDate billOfEntryDate;
  private BigDecimal billOfEntryValue;
  private String documentType;
  private String sezSupplierGstin;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal integratedTaxPaid;
  private BigDecimal cessPaid;
  private String itcEligibility;
  private BigDecimal availedItcIntegrated;
  private BigDecimal availedItcCess;
}
