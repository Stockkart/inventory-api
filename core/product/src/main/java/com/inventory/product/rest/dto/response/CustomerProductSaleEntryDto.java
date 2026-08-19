package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.enums.SchemeType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerProductSaleEntryDto {
  private Instant soldAt;
  private String invoiceNo;
  private String purchaseId;
  private BigDecimal quantity;
  private BigDecimal priceToRetail;
  private BigDecimal lineTotal;
  /** Sale additional discount percent applied on the line (positive = discount). */
  private BigDecimal saleAdditionalDiscount;
  private SchemeType schemeType;
  private Integer schemePayFor;
  private Integer schemeFree;
  private BigDecimal schemePercentage;
}
