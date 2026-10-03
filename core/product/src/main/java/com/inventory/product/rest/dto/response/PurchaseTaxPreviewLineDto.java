package com.inventory.product.rest.dto.response;

import java.math.BigDecimal;
import lombok.Data;

/** One item row's taxable value and tax, in the order the rows were sent. */
@Data
public class PurchaseTaxPreviewLineDto {
  private BigDecimal taxable;
  private BigDecimal ratePct;
  private BigDecimal centralTax;
  private BigDecimal stateTax;
  private BigDecimal integratedTax;
  private BigDecimal tax;
}
