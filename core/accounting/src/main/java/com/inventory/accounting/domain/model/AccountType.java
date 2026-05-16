package com.inventory.accounting.domain.model;

/**
 * High-level classification of a chart-of-accounts entry. Drives where the account appears in the
 * Trial Balance, P&amp;L and Balance Sheet reports.
 */
public enum AccountType {
  ASSET,
  LIABILITY,
  EQUITY,
  REVENUE,
  EXPENSE
}
