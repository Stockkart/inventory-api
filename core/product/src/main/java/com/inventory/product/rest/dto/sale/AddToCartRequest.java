package com.inventory.product.rest.dto.sale;

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
    private BigDecimal sellingPrice;
    /** Optional: additional discount percentage for this item (0–100). Overrides inventory default when provided. */
    private BigDecimal additionalDiscount;
  }
}

