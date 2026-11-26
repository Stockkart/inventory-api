package com.inventory.common.entitlement;

/**
 * Capabilities a plan or add-on can grant. Features every plan includes are not listed.
 *
 * <p>Lives in {@code common} so feature modules can reference it without depending on {@code plan}.
 */
public enum PlanFeature {
  CREDIT_BALANCE,
  ACCOUNTING,
  BARCODE_GENERATOR,
  LOW_STOCK_NOTIFICATION,
  MARKETING,
  SALARY,
  BIOMETRIC_ATTENDANCE,
  ADVANCED_ACCESS_CONTROL,
  /** Shop logo and digital signature on invoices. Shown on the pricing page; not gated yet. */
  SHOP_BRANDING,
  /** Priority support. A service promise shown on the pricing page; nothing to gate. */
  PRIORITY_SUPPORT
}
