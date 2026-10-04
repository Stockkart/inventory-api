package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One refunded line for list/history views */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundSummaryItemDto {

  private String inventoryId;

  private String name;

  private Integer quantity;

  private BigDecimal priceToRetail;

  private BigDecimal itemRefundAmount;

  // --- The line as it was billed, so a note reads like the invoice it credits ---
  /** MRP printed on the original invoice line. */
  private BigDecimal maximumRetailPrice;
  /** Additional discount percentage applied on the sale. */
  private BigDecimal saleAdditionalDiscount;
  /** GST rates as charged, e.g. "2.5" each. */
  private String sgst;
  private String cgst;
  /** Sale scheme as billed: FIXED_UNITS with payFor/free, or PERCENTAGE. */
  private String schemeType;
  private Integer schemePayFor;
  private Integer schemeFree;
  private BigDecimal schemePercentage;
  /** Value the credited tax is charged on, and the tax itself. */
  private BigDecimal taxableValue;
  private BigDecimal cgstAmount;
  private BigDecimal sgstAmount;
}
