package com.inventory.accounting.domain.model;

/** Originating business event a {@code JournalEntry} reflects. */
public enum JournalSource {
  OPENING_BALANCE,
  VENDOR_PURCHASE_INVOICE,
  VENDOR_PURCHASE_RETURN,
  SALE,
  SALES_RETURN,
  CUSTOMER_SETTLEMENT,
  VENDOR_PAYMENT,
  /** Manual or UI-driven increase in vendor payable (credit charge). */
  VENDOR_CREDIT_CHARGE,
  /** Manual or UI-driven increase in customer receivable (credit charge). */
  CUSTOMER_CREDIT_CHARGE,
  INVENTORY_CORRECTION,
  MANUAL,
  REVERSAL
}
