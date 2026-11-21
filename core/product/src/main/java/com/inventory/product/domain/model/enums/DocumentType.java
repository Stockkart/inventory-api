package com.inventory.product.domain.model.enums;

/**
 * Kind of purchase document. Orthogonal to {@link BillingMode} (tax math / PDF field set) and
 * {@link PurchaseStatus} (cart lifecycle).
 *
 * <ul>
 *   <li>{@link #SALE} — normal sell quotation / invoice path
 *   <li>{@link #ESTIMATE} — printable quote; OPEN estimates soft-reserve stock; lock then print;
 *       convert to a SALE cart when lines are invoice-eligible
 * </ul>
 */
public enum DocumentType {
  SALE,
  ESTIMATE
}
