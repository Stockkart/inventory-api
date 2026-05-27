package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.enums.SchemeType;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One past sale of a single inventory line to the same customer. Used by scan-and-sell to
 * surface "last bought at" pricing so the cashier can match prior rates without searching.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerProductHistoryEntry {
  private String purchaseId;
  private String invoiceNo;
  private Instant soldAt;
  private String inventoryId;
  private String name;
  private BigDecimal quantity;
  private String saleUnit;
  private BigDecimal priceToRetail;
  private BigDecimal saleAdditionalDiscount;
  private BigDecimal totalAmount;
  private String cgst;
  private String sgst;
  private SchemeType schemeType;
  private Integer schemePayFor;
  private Integer schemeFree;
  private BigDecimal schemePercentage;
}
