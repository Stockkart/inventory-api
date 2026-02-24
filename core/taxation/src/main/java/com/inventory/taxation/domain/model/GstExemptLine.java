package com.inventory.taxation.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Nil / exempt / non-GST supply line for GSTR-1 exemp tab.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GstExemptLine {

  private String description;
  private BigDecimal nilRatedSupplies;
  private BigDecimal exemptedOtherThanNilOrNonGst;
  private BigDecimal nonGstSupplies;
}
