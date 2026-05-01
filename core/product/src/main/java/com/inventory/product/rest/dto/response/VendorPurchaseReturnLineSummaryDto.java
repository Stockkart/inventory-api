package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One inventory line on a supplier purchase return (history view). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorPurchaseReturnLineSummaryDto {

  private String inventoryId;

  /** From inventory or invoice line when inventory is missing. */
  private String productName;

  private String barcode;

  private Integer baseQuantityReturned;

  private BigDecimal taxableValue;

  private BigDecimal centralGstAmount;

  private BigDecimal stateGstAmount;

  /** Line total incl. tax snapshot. */
  private BigDecimal lineNoteValue;
}
