package com.inventory.product.rest.dto.sale;

import com.inventory.product.domain.model.SchemeType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class AddToCartRequest {

  private String businessType;
  private List<CartItem> items;
  // Customer info (optional)
  private String customerName;
  private String customerAddress;
  private String customerPhone;
  private String customerEmail;
  private String customerGstin; // Optional: Customer GSTIN
  private String customerDlNo; // Optional: Customer D.L No.
  private String customerPan; // Optional: Customer PAN

  @Data
  public static class CartItem {
    private String id;
    private Integer quantity;
    /** Optional base-unit quantity (e.g. tabs/ml) for loose sale. */
    private Integer baseQuantity;
    /** Optional sale/display unit (e.g. STRIP, PACK). Defaults to inventory baseUnit when omitted. */
    private String unit;
    private BigDecimal sellingPrice;
    /** Optional: additional discount percentage for this item (0–100). Overrides inventory default when provided. */
    private BigDecimal additionalDiscount;
    /**
     * Optional: scheme type for selling.
     * - FIXED_UNITS: use schemePayFor / schemeFree (e.g. \"2 free on 10\").
     * - PERCENTAGE: use schemePercentage (e.g. 10 = 10% extra free).
     */
    private SchemeType schemeType;
    /** Optional when schemeType is FIXED_UNITS: pay for this many (e.g. 10). With schemeFree, \"schemeFree free on schemePayFor\". */
    private Integer schemePayFor;
    /** Optional when schemeType is FIXED_UNITS: free units per batch (e.g. 2). With schemePayFor=10 = \"2 free on 10\". */
    private Integer schemeFree;
    /** Optional when schemeType is PERCENTAGE: e.g. 10 = 10% extra free. */
    private BigDecimal schemePercentage;
  }
}

