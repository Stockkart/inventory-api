package com.inventory.plan.domain.model;

/**
 * How a catalog row is charged.
 *
 * <p>{@link #USAGE} rows are priced at the point of use (e.g. pay-per-SMS) and carry no headline
 * price, so clients render them without a period suffix.
 */
public enum PlanBillingPeriod {
  YEAR,
  MONTH,
  USAGE
}
