package com.inventory.product.utils.constants;

/**
 * Metric names for Prometheus/Grafana. Use with MetricsWrapper.record().
 */
public final class ProductMetricsConstants {

  private ProductMetricsConstants() {}

  public static final String MODULE = "product";

  // Orders / Checkout
  public static final String ORDERS_COMPLETED = "inventory_product_orders_completed_total";
  public static final String ORDERS_AMOUNT = "inventory_product_orders_amount";
  public static final String CART_CREATED = "inventory_product_cart_created_total";
  public static final String CART_UPDATED = "inventory_product_cart_updated_total";

  // Inventory
  public static final String INVENTORY_OPERATION = "inventory_product_inventory_operations_total";
  public static final String INVENTORY_ITEMS_ADDED = "inventory_product_inventory_items_added";

  // Refunds
  public static final String REFUNDS_TOTAL = "inventory_product_refunds_total";
  public static final String REFUND_AMOUNT = "inventory_product_refund_amount";

  // Invoices
  public static final String INVOICES_GENERATED = "inventory_product_invoices_generated_total";
  public static final String INVENTORY_CORRECTIONS = "inventory_product_inventory_corrections_total";
  public static final String VENDOR_PURCHASES = "inventory_product_vendor_purchases_total";
  public static final String VENDOR_RETURNS = "inventory_product_vendor_returns_total";
  public static final String QUOTATIONS_TOTAL = "inventory_product_quotations_total";
  public static final String CREDIT_NOTES_TOTAL = "inventory_product_credit_notes_total";
  public static final String BARCODES_GENERATED = "inventory_product_barcodes_generated_total";
  public static final String QR_TOKENS_TOTAL = "inventory_product_qr_tokens_total";
}
