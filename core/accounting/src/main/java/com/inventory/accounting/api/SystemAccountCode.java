package com.inventory.accounting.api;

/**
 * Stable identifiers for system-seeded accounts. Codes are hierarchical so they roll up by prefix
 * (1xxx Assets, 2xxx Liabilities, 3xxx Equity, 4xxx Revenue, 5xxx Expenses). They are stored on
 * {@code Account.code} and referenced from posting templates so the system never has to look an
 * account up by name.
 */
public interface SystemAccountCode {


  // Assets
  String CASH = "1100";
  String BANK = "1110";
  String CARD_CLEARING = "1120";
  String UPI_CLEARING = "1130";
  String SUNDRY_DEBTORS = "1200";
  /** Salary paid to staff ahead of payroll (HR advances), recovered from later pay. */
  String STAFF_ADVANCES = "1250";
  String INVENTORY = "1300";
  String INPUT_CGST = "1400";
  String INPUT_SGST = "1410";
  String INPUT_IGST = "1420";

  // Liabilities
  String SUNDRY_CREDITORS = "2100";
  String OUTPUT_CGST = "2200";
  String OUTPUT_SGST = "2210";
  String OUTPUT_IGST = "2220";
  String ROUND_OFF_PAYABLE = "2300";
  /** Net pay of finalised HR payroll runs not yet paid out. */
  String SALARY_PAYABLE = "2400";

  // Equity
  String OWNERS_CAPITAL = "3100";
  String RETAINED_EARNINGS = "3200";

  // Revenue
  String SALES = "4100";
  String SALES_RETURNS = "4200";
  String DISCOUNT_ALLOWED = "4300";
  /** Vendor settlement write-off / discount in our favour (credit side of vendor payment). */
  String DISCOUNT_RECEIVED = "4350";

  // Expenses
  String PURCHASES = "5100";
  String PURCHASE_RETURNS = "5200";
  String COGS = "5300";
  String SHIPPING_FREIGHT = "5400";
  String ROUND_OFF_EXPENSE = "5500";
  /** Customer settlement shortfall / bad debt write-off. */
  String BAD_DEBTS_WRITTEN_OFF = "5600";
  /** HR payroll: pay before the advance deduction and before net-pay rounding (R11.1). */
  String SALARY_EXPENSE = "5700";
  String OTHER_OPERATING_EXPENSES = "5900";
}
