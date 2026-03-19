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

  // Errors
  public static final String EXCEPTIONS_TOTAL = "inventory_product_exceptions_total";

  // External
  public static final String EXTERNAL_CALLS_TOTAL = "inventory_product_external_calls_total";
  public static final String EXTERNAL_FAILURES_TOTAL = "inventory_product_external_failures_total";
}
