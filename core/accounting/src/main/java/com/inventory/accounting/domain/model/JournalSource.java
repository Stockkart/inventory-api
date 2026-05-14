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
  INVENTORY_CORRECTION,
  MANUAL,
  REVERSAL
}
