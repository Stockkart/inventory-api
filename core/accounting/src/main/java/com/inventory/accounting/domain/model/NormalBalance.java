package com.inventory.accounting.domain.model;

/**
 * Side on which an account normally carries a positive balance. Assets &amp; Expenses are DEBIT;
 * Liabilities, Equity &amp; Revenue are CREDIT. Contra-accounts intentionally invert this.
 */
public enum NormalBalance {
  DEBIT,
  CREDIT
}
