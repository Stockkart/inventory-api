package com.inventory.taxation.utils.constants;

public final class TaxationMetricsConstants {

  private TaxationMetricsConstants() {}

  public static final String MODULE = "taxation";

  public static final String REPORTS_TOTAL = "inventory_taxation_reports_total";
  /** GSTIN lookups by outcome: registry_hit, network_found, network_unknown, network_error, invalid. */
  public static final String GSTIN_LOOKUPS_TOTAL = "inventory_taxation_gstin_lookups_total";
  /** Credits the provider says are left on the account, as last reported. */
  public static final String GSTIN_CREDITS_REMAINING = "inventory_taxation_gstin_credits_remaining";
}
