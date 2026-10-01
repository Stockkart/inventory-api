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
  /** Taxable value of the items, after purchase scheme and additional discount. */
  private BigDecimal lineSubTotal;
  private BigDecimal taxTotal;
  /** Items only: the taxable value plus its tax, before header charges and discount. */
  private BigDecimal itemsTotal;
  /** Typed subtotal and tax where given, else the resolved ones, plus charges, less discount. */
  private BigDecimal invoiceTotal;
  private int productCount;
  private int totalQuantity;
  private List<PurchaseTaxPreviewLineDto> lines;
}
