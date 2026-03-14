package com.inventory.reminders.utils.constants;

import java.time.Duration;

/**
 * Constants for event dispatch and retry logic.
 */
public final class EventConstants {

  private EventConstants() {}

  /** Maximum retry attempts for undelivered events. */
  public static final int MAX_RETRIES = 3;

  /** Base seconds for exponential backoff. */
  public static final long BASE_RETRY_SECONDS = 10L;

  /** Maximum backoff duration in seconds. */
  public static final long MAX_BACKOFF_SECONDS = Duration.ofDays(1).getSeconds();

  /** Days to wait before retrying an exhausted event (manual handling). */
  public static final long EXHAUSTED_RETRY_DAYS = 30L;
}
