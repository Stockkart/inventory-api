package com.inventory.accounting.api;

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
  /** HR payroll run finalised; sourceId {@code runId:revision}. */
  PAYROLL_RUN,
  /** HR salary advance given; sourceId = advance id. */
  STAFF_ADVANCE,
  /**
   * HR salary paid; sourceId = payment batch id, or {@code undo:<lineId>:<paymentSeq>} for the
   * compensating entry of one undone line.
   */
  SALARY_PAYMENT,
  INVENTORY_CORRECTION,
  MANUAL,
  REVERSAL
}
