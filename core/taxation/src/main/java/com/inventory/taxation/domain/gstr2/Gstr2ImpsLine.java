package com.inventory.taxation.domain.gstr2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * IMPS - Import of services (Table 4C).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gstr2ImpsLine {

  private String invoiceNo;
  private LocalDate invoiceDate;
  private BigDecimal invoiceValue;
  private String placeOfSupply;
  private BigDecimal rate;
  private BigDecimal taxableValue;
  private BigDecimal integratedTaxPaid;
  private BigDecimal cessPaid;
  private String itcEligibility;
  private BigDecimal availedItcIntegrated;
  private BigDecimal availedItcCess;
}
