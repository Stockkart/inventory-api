package com.inventory.hr.utils.constants;

/** HR metric names (N7). All are counters tagged {@code module=hr}. */
public final class HrMetricsConstants {

  private HrMetricsConstants() {}

  public static final String MODULE = "hr";

  public static final String PAYROLL_FINALISE_TOTAL = "inventory_hr_payroll_finalise_total";
  public static final String PAYROLL_REOPEN_TOTAL = "inventory_hr_payroll_reopen_total";
  public static final String SALARY_PAYMENTS_TOTAL = "inventory_hr_salary_payments_total";
  public static final String ATTENDANCE_WRITES_TOTAL = "inventory_hr_attendance_writes_total";
  public static final String AUDIT_FAILURES_TOTAL = "inventory_hr_audit_failures_total";
  public static final String JOURNAL_POST_FAILURES_TOTAL =
      "inventory_hr_journal_post_failures_total";
  public static final String QUERY_TIMEOUTS_TOTAL = "inventory_hr_query_timeouts_total";
  public static final String PAYROLL_CALC_SECONDS_TOTAL = "inventory_hr_payroll_calc_seconds_total";
  public static final String PAYROLL_CALC_TOTAL = "inventory_hr_payroll_calc_total";
  public static final String DOCUMENTS_SECONDS_TOTAL = "inventory_hr_documents_seconds_total";
  public static final String DOCUMENTS_TOTAL = "inventory_hr_documents_total";
  /** An HR post refused because the shop has its own account on 1250/2400/5700; tag code. */
  public static final String ACCOUNT_CONFLICTS_TOTAL = "inventory_hr_account_conflicts_total";
}
