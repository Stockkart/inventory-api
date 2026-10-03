package com.inventory.product.rest.dto.request;

import com.inventory.common.constants.PurchaseTaxTreatment;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

/**
 * The stock-in screen as it stands, before it is submitted: the same item rows
 * {@code POST /inventory/bulk} takes, the vendor, how the bill states tax, and whatever header
 * figures have been typed so far (all optional).
 */
@Data
public class PurchaseTaxPreviewRequest {
  private String vendorId;
  private PurchaseTaxTreatment taxTreatment;
  private List<CreateInventoryItemRequest> items;

  /** Typed header figures; an empty subtotal or tax falls back to the figure resolved from items. */
  private BigDecimal lineSubTotal;
  private BigDecimal taxTotal;
  private BigDecimal shippingCharge;
  private BigDecimal otherCharges;
  private BigDecimal overallDiscount;
  private BigDecimal roundOff;
}
