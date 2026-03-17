package com.inventory.taxation.domain.gstr2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * B2BUR inward supply line - supplies from unregistered suppliers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gstr2B2burLine {

  private String supplierName;
  private String invoiceNo;
  private LocalDate invoiceDate;
  private BigDecimal invoiceValue;
  private String placeOfSupply;
  private String supplyType;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal integratedTaxPaid;
  private BigDecimal centralTaxPaid;
  private BigDecimal stateUtTaxPaid;
  private BigDecimal cessAmount;
  private String itcEligibility;
  private BigDecimal availedItcIntegrated;
  private BigDecimal availedItcCentral;
  private BigDecimal availedItcStateUt;
  private BigDecimal availedItcCess;
}
