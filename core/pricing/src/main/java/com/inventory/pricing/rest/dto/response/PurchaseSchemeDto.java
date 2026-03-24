package com.inventory.pricing.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Purchase scheme/deal from vendor. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseSchemeDto {
  private String schemeType;
  private Integer schemePayFor;
  private Integer schemeFree;
  private BigDecimal schemePercentage;
}
