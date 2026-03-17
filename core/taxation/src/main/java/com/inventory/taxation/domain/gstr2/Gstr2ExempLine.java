package com.inventory.taxation.domain.gstr2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * EXEMP - Composition, nil rated, exempt, non-GST supplies received.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gstr2ExempLine {

  private String description;
  private BigDecimal compositionTaxablePerson;
  private BigDecimal nilRatedSupplies;
  private BigDecimal exemptedOtherThanNilOrNonGst;
  private BigDecimal nonGstSupplies;
}
