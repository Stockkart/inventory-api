package com.inventory.pricing.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Scheme/deal (purchase or sale). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SchemeDto {
  private String schemeType;
  private Integer schemePayFor;
  private Integer schemeFree;
  private BigDecimal schemePercentage;
}
