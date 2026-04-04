package com.inventory.product.rest.dto.request;

import lombok.Data;

import java.util.List;

/**
 * Request DTO for bulk inventory creation.
 * vendorId is shared across all items. Stock-in is grouped by vendor purchase invoice id (created per bulk).
 */
@Data
public class BulkCreateInventoryRequest {
  // Shared fields applied to all items
  private String vendorId;
  /** When true, record this purchase as credit (buyer owes vendor). When false, treated as paid/cash. */
  private Boolean onCredit;
  /** When vendor is a StockKart user and purchase is on credit, assign to vendor's shop. */
  private String vendorShopId;

  /**
   * Optional vendor invoice metadata. When absent or without invoice number, an AUTO-* synthetic invoice is created.
   */
  private VendorPurchaseInvoiceRequest vendorPurchaseInvoice;

  // List of inventory items (without vendorId and lotId)
  private List<CreateInventoryItemRequest> items;
}

