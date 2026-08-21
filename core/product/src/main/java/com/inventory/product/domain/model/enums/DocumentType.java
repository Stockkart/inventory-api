package com.inventory.product.domain.model.enums;

/**
 * Kind of purchase document. Orthogonal to {@link BillingMode} (tax math / PDF field set) and
 * {@link PurchaseStatus} (cart lifecycle).
 *
 * <ul>
 *   <li>{@link #SALE} — normal sell quotation / invoice path
 *   <li>{@link #ESTIMATE} — printable quote; does not reserve stock; converts one-way to a SALE cart
 * </ul>
 */
public enum DocumentType {
  SALE,
  ESTIMATE
}
