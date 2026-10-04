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
  /** The treatment applied: stated, else read from cost against MRP, else the vendor's usual; null reads as exclusive. */
  private PurchaseTaxTreatment taxTreatment;
  /**
   * Where the treatment came from: STATED (the bill), LINES (cost at MRP is inclusive, below MRP
   * exclusive), VENDOR (its usual convention) or NONE.
   */
  private String taxTreatmentSource;
  /** What cost against MRP says on the rows; null when they cannot decide. */
  private PurchaseTaxTreatment taxTreatmentFromLines;
  /**
   * Set when the treatment applied contradicts the rows: the message to show the operator.
   * Stock-in refuses such a bill unless {@code confirmTaxTreatment} is sent.
   */
  private String taxTreatmentConflict;
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
