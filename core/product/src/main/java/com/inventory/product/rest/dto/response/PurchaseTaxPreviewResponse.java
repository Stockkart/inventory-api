package com.inventory.product.rest.dto.response;

import com.inventory.common.constants.PurchaseTaxTreatment;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

/**
 * What the bill on screen comes to, worked out the way stock-in will record it. The stock-in
 * screen shows these figures rather than computing its own.
 */
@Data
public class PurchaseTaxPreviewResponse {
  /** The treatment applied: the one stated, else the vendor's default; null reads as exclusive. */
  private PurchaseTaxTreatment taxTreatment;
  /** Taxable value of the items after scheme and additional discount, before the bill-level discount. */
  private BigDecimal lineSubTotal;
  private BigDecimal taxTotal;
  /** Items only: the taxable value plus its tax, before header charges and discount. */
  private BigDecimal itemsTotal;
  /** Taxable value after the bill-level discount, plus tax, shipping, other charges and round-off. */
  private BigDecimal invoiceTotal;
  private int productCount;
  private int totalQuantity;
  private List<PurchaseTaxPreviewLineDto> lines;
}
