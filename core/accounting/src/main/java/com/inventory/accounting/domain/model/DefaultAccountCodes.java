package com.inventory.accounting.domain.model;

import java.util.Set;

/**
 * Built-in nominal account codes seeded per shop (legacy aliases with prefixes are renamed on
 * bootstrap — see {@code LegacyGlChartCodeMigrator}).
 */
public final class DefaultAccountCodes {

  private DefaultAccountCodes() {}

  public static final String CASH = "CASH";
  public static final String ACCOUNTS_RECEIVABLE = "RECEIVABLES";
  public static final String ACCOUNTS_PAYABLE = "PAYABLES";
  public static final String OWNER_EQUITY = "EQUITY";
  public static final String SALES_REVENUE = "SALES";

  /** Ex-GST stock purchase cost posted on vendor invoices (credit purchases accrue payable, not cash). */
  public static final String PURCHASES_EXPENSE = "PURCHASES";

  /** All collected GST output (SGST+CGST+blended totals) posts to this single liability account. */
  public static final String GST_OUTPUT_COMBINED = "GST-OUTPUT";

  public static final String GST_INPUT_COMBINED = "GST-INPUT";

  /** Codes users cannot reuse when creating manual GL accounts ({@link GlAccountWriteService}). */
  public static Set<String> seededCodesUppercase() {
    return Set.of(
        CASH,
        ACCOUNTS_RECEIVABLE,
        ACCOUNTS_PAYABLE,
        OWNER_EQUITY,
        SALES_REVENUE,
        PURCHASES_EXPENSE,
        GST_OUTPUT_COMBINED,
        GST_INPUT_COMBINED);
  }
}
