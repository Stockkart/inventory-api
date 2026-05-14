package com.inventory.accounting.domain.model;

/**
 * Stable identifiers for system-seeded accounts. Codes are hierarchical so they roll up by prefix
 * (1xxx Assets, 2xxx Liabilities, 3xxx Equity, 4xxx Revenue, 5xxx Expenses). They are stored on
 * {@code Account.code} and referenced from posting templates so the system never has to look an
 * account up by name.
 */
public final class SystemAccountCode {

  private SystemAccountCode() {}

  // Assets
  public static final String CASH = "1100";
  public static final String BANK = "1110";
  public static final String CARD_CLEARING = "1120";
  public static final String UPI_CLEARING = "1130";
  public static final String SUNDRY_DEBTORS = "1200";
  public static final String INVENTORY = "1300";
  public static final String INPUT_CGST = "1400";
  public static final String INPUT_SGST = "1410";
  public static final String INPUT_IGST = "1420";

  // Liabilities
  public static final String SUNDRY_CREDITORS = "2100";
  public static final String OUTPUT_CGST = "2200";
  public static final String OUTPUT_SGST = "2210";
  public static final String OUTPUT_IGST = "2220";
  public static final String ROUND_OFF_PAYABLE = "2300";

  // Equity
  public static final String OWNERS_CAPITAL = "3100";
  public static final String RETAINED_EARNINGS = "3200";

  // Revenue
  public static final String SALES = "4100";
  public static final String SALES_RETURNS = "4200";
  public static final String DISCOUNT_ALLOWED = "4300";

  // Expenses
  public static final String PURCHASES = "5100";
  public static final String PURCHASE_RETURNS = "5200";
  public static final String COGS = "5300";
  public static final String SHIPPING_FREIGHT = "5400";
  public static final String ROUND_OFF_EXPENSE = "5500";
  public static final String OTHER_OPERATING_EXPENSES = "5900";
}
