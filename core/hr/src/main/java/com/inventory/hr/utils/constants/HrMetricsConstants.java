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
  /**
   * Self check-in attempts; tag {@code result} = accepted | flagged | duplicate | off |
   * not_linked | no_permission | not_employed | on_leave | locked | outside_shop |
   * location_unclear | invalid | error.
   */
  public static final String SELF_CHECKIN_TOTAL = "inventory_hr_self_checkin_total";
  /**
   * Attendance app punch attempts, one increment per attempt (R10.1–R10.2); tags {@code shop} and
   * {@code result} = accepted | flagged | duplicate | replayed | off | not_paired | not_employed |
   * on_leave | locked | outside_shop | location_unclear | fake_location | offline_accepted |
   * offline_too_late | offline_clock_wrong | signature_failed | rate_limited | invalid | error.
   */
  public static final String APP_PUNCHES_TOTAL = "inventory_hr_app_punches_total";
  /**
   * Attendance app pairing events, one increment per event (R10.3); tags {@code shop}, {@code
   * event} = created | completed | replaced | unpaired | failed | attestation, and {@code reason}:
   * for failed one of the R2.14 reasons (code_expired | code_used | code_cancelled | code_wrong |
   * app_off | employee_exited | abandoned | locked | wrong_employee), for unpaired the {@code UnpairReason} name,
   * for attestation {@code ok} or a {@code KeyAttestation.Reason} (task 16.4), else "-".
   */
  public static final String APP_PAIRINGS_TOTAL = "inventory_hr_app_pairings_total";
  /**
   * An accepted offline app punch that arrived more than 1 hour after its phone time (R10.7b);
   * tag {@code shop}.
   */
  public static final String APP_OFFLINE_BACKLOG_TOTAL = "inventory_hr_app_offline_backlog_total";

  // Stockkart Team (worker-app R14.2). All tagged module=hr; shop only where one is known.
  /** A WORKER account was created from the app; no shop tag (the account has no shop yet). */
  public static final String WORKER_SIGNUPS_TOTAL = "inventory_hr_worker_signups_total";
  /**
   * Worker join request events, one increment per event; tags {@code shop} and {@code event} =
   * created | approved_created | approved_linked | rejected | withdrawn. Over any period: created =
   * approved_created + approved_linked + rejected + withdrawn + still pending (Property 29).
   */
  public static final String WORKER_JOIN_REQUESTS_TOTAL = "inventory_hr_worker_join_requests_total";
  /** Shop lookups by owner email; tag {@code result} = found | not_found. No shop tag. */
  public static final String WORKER_LOOKUPS_TOTAL = "inventory_hr_worker_lookups_total";
  /** Sign-ins with {@code source = worker-app}; tag {@code result} = success | error. No shop tag. */
  public static final String WORKER_APP_LOGINS_TOTAL = "inventory_hr_worker_app_logins_total";

  /** Tag keys shared by the app counters. */
  public static final String TAG_SHOP = "shop";
  public static final String TAG_RESULT = "result";
  public static final String TAG_EVENT = "event";
  public static final String TAG_REASON = "reason";

  /** {@code event} values of {@link #WORKER_JOIN_REQUESTS_TOTAL}. */
  public static final class WorkerJoinEvent {
    private WorkerJoinEvent() {}

    public static final String CREATED = "created";
    public static final String APPROVED_CREATED = "approved_created";
    public static final String APPROVED_LINKED = "approved_linked";
    public static final String REJECTED = "rejected";
    public static final String WITHDRAWN = "withdrawn";
  }

  /** {@code result} values of {@link #WORKER_LOOKUPS_TOTAL}. */
  public static final class WorkerLookupResult {
    private WorkerLookupResult() {}

    public static final String FOUND = "found";
    public static final String NOT_FOUND = "not_found";
  }

  /** {@code result} values of {@link #WORKER_APP_LOGINS_TOTAL}. */
  public static final class WorkerLoginResult {
    private WorkerLoginResult() {}

    public static final String SUCCESS = "success";
    public static final String ERROR = "error";
  }

  /** {@code result} values of {@link #APP_PUNCHES_TOTAL}. */
  public static final class AppPunchResult {
    private AppPunchResult() {}

    public static final String ACCEPTED = "accepted";
    public static final String FLAGGED = "flagged";
    public static final String DUPLICATE = "duplicate";
    public static final String REPLAYED = "replayed";
    public static final String OFF = "off";
    public static final String NOT_PAIRED = "not_paired";
    public static final String NOT_EMPLOYED = "not_employed";
    public static final String ON_LEAVE = "on_leave";
    public static final String LOCKED = "locked";
    public static final String OUTSIDE_SHOP = "outside_shop";
    public static final String LOCATION_UNCLEAR = "location_unclear";
    public static final String FAKE_LOCATION = "fake_location";
    public static final String OFFLINE_ACCEPTED = "offline_accepted";
    public static final String OFFLINE_TOO_LATE = "offline_too_late";
    public static final String OFFLINE_CLOCK_WRONG = "offline_clock_wrong";
    public static final String SIGNATURE_FAILED = "signature_failed";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String INVALID = "invalid";
    public static final String ERROR = "error";
  }

  /** {@code event} values of {@link #APP_PAIRINGS_TOTAL}. */
  public static final class AppPairingEvent {
    private AppPairingEvent() {}

    public static final String CREATED = "created";
    public static final String COMPLETED = "completed";
    public static final String REPLACED = "replaced";
    public static final String UNPAIRED = "unpaired";
    public static final String FAILED = "failed";
    /**
     * A key attestation chain was sent with {@code complete} (task 16.4); {@code reason} is {@link
     * AppPairingAttestation#OK} or the failed check. Counted whether or not the pairing went ahead
     * (it always does in this version).
     */
    public static final String ATTESTATION = "attestation";
  }

  /**
   * {@code reason} values of {@link #APP_PAIRINGS_TOTAL} when {@code event=attestation}; the same
   * strings as {@code KeyAttestation.Reason}, listed here so the dashboard has one place to read.
   */
  public static final class AppPairingAttestation {
    private AppPairingAttestation() {}

    public static final String OK = "ok";
    public static final String CHAIN_EMPTY = "chain_empty";
    public static final String CHAIN_UNPARSEABLE = "chain_unparseable";
    public static final String CHAIN_BROKEN = "chain_broken";
    public static final String UNTRUSTED_ROOT = "untrusted_root";
    public static final String NO_EXTENSION = "no_extension";
    public static final String EXTENSION_MALFORMED = "extension_malformed";
    public static final String SOFTWARE_LEVEL = "software_level";
    public static final String CHALLENGE_MISMATCH = "challenge_mismatch";
    public static final String NOT_SIGN = "not_sign";
    public static final String NOT_EC = "not_ec";
    public static final String NOT_P256 = "not_p256";
    public static final String NO_BIOMETRIC = "no_biometric";
    public static final String AUTH_TIMEOUT = "auth_timeout";
    public static final String KEY_MISMATCH = "key_mismatch";
    public static final String ERROR = "error";
  }

  /** {@code reason} values of {@link #APP_PAIRINGS_TOTAL} when {@code event=failed} (R2.14). */
  public static final class AppPairingFailure {
    private AppPairingFailure() {}

    public static final String CODE_EXPIRED = "code_expired";
    public static final String CODE_USED = "code_used";
    public static final String CODE_CANCELLED = "code_cancelled";
    public static final String CODE_WRONG = "code_wrong";
    public static final String APP_OFF = "app_off";
    public static final String EMPLOYEE_EXITED = "employee_exited";
    /** "No, this isn't me" or "Don't agree" on the phone. */
    public static final String ABANDONED = "abandoned";
    /** Installation lockout or the 11th attempt on one code. */
    public static final String LOCKED = "locked";
    /** The code is for an employee other than the signed-in worker's (worker-app R7.3). */
    public static final String WRONG_EMPLOYEE = "wrong_employee";
    /** {@code reason} value for events that have none. */
    public static final String NONE = "-";
  }
}
