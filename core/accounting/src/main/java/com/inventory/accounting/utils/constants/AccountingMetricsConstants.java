package com.inventory.accounting.utils.constants;

public final class AccountingMetricsConstants {

  private AccountingMetricsConstants() {}

  public static final String MODULE = "accounting";

  public static final String JOURNALS_TOTAL = "inventory_accounting_journals_total";
  public static final String ACCOUNTS_TOTAL = "inventory_accounting_accounts_total";
  public static final String BACKFILL_TOTAL = "inventory_accounting_backfill_total";
  /** A start-up index could not be created (e.g. duplicates in old data); tag {@code index}. */
  public static final String INDEX_FAILURES_TOTAL = "inventory_accounting_index_failures_total";
}
