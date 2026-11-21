package com.inventory.common.heavy;

/**
 * Kinds of request that hold a lot of memory or wait on a slow provider, each limited separately by
 * {@link HeavyRequestLimiter}. Defaults are the measured-safe values for one 1 GiB container
 * (load test 2026-10-23); each can be overridden with {@code heavy.<kind>.*} (env {@code HEAVY_<KIND>_*}).
 */
public enum HeavyKind {
  /** MIS PDF / Excel and GSTR downloads: a whole period built in memory. */
  EXPORT("export", 1, 3, 20_000, 0, 30,
      "Another report is being prepared. Please try again in a minute."),
  /** Analytics screens: every lot or a period's sale lines, a batch at a time. */
  ANALYTICS("analytics", 3, 6, 10_000, 0, 10,
      "Analytics are busy right now. Please try again in a few seconds."),
  /** Invoice photo parsing: images in memory, provider rate-limited (Gemini 429 at ~5 at once). */
  OCR("ocr", 2, 4, 15_000, 300_000, 15,
      "Invoice reading is busy right now. Please try again in a moment."),
  /** Excel stock import and price bulk update: a whole sheet in memory. */
  IMPORT("import", 1, 2, 20_000, 0, 30,
      "Another import is running. Please try again in a minute."),
  /**
   * HR month export, leave-balance export, salary register, payroll create / recalculate /
   * finalise: a month of attendance (≤ 9,300 day records for 300 employees) in memory. Its own
   * kind so HR files never wait behind a slow MIS export (HR design decision 9).
   */
  HR("hr", 2, 4, 10_000, 0, 10,
      "HR is preparing another file or payroll. Please try again in a few seconds.");

  /** Property / metric name. */
  public final String key;
  /** Requests of this kind run at once. */
  public final int defaultLimit;
  /** Web requests that may wait for a slot; one more gets "busy" at once. */
  public final int defaultQueue;
  /** How long a web request waits for a slot. */
  public final long defaultWaitMs;
  /** How long background work (no web request, e.g. the phone upload's parsing) waits; 0 = same as web. */
  public final long defaultBackgroundWaitMs;
  /** {@code Retry-After} on the busy answer. */
  public final int retryAfterSeconds;
  /** Shown to the user on the busy answer. */
  public final String busyMessage;

  HeavyKind(String key, int defaultLimit, int defaultQueue, long defaultWaitMs, long defaultBackgroundWaitMs,
      int retryAfterSeconds, String busyMessage) {
    this.key = key;
    this.defaultLimit = defaultLimit;
    this.defaultQueue = defaultQueue;
    this.defaultWaitMs = defaultWaitMs;
    this.defaultBackgroundWaitMs = defaultBackgroundWaitMs;
    this.retryAfterSeconds = retryAfterSeconds;
    this.busyMessage = busyMessage;
  }
}
