package com.inventory.user.api.port;

/**
 * What sign-up and sign-in need from HR for Stockkart Team (worker-app R14.1, R14.2; design
 * DD11): the per-IP sign-up limit and the two worker counters. Both live in HR ({@code
 * AppRateLimiter}, {@code HrMetricsConstants}) and HR sits above user, so HR implements this port in
 * its {@code service/api/} package and the user module calls it without knowing either. Without an
 * implementation (a test slice, a build without HR) every sign-up is admitted and nothing is
 * counted; the module still works.
 */
public interface WorkerAppAuthPort {

  /** Sign-ups from the app admitted per client IP per hour (R14.1). */
  int SIGNUP_IP_LIMIT = 20;

  /**
   * Admits or refuses one sign-up from the Stockkart Team app by the client's IP address: at most
   * {@link #SIGNUP_IP_LIMIT} in any rolling hour per address. A refused call is not recorded, so a
   * client hammering the limit is not pushed further back.
   *
   * @param clientIp the address as the server saw it; a blank value shares one bucket
   * @return true when the sign-up may go ahead
   */
  boolean admitSignup(String clientIp);

  /** One WORKER account was created ({@code worker_signups_total}). */
  void workerSignedUp();

  /**
   * One sign-in from the Stockkart Team app ended ({@code worker_app_logins_total{result}}).
   *
   * @param success true when a session was issued, false for any refusal
   */
  void appLogin(boolean success);
}
