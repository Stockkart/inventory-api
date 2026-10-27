package com.inventory.notifications.constants;

import java.time.Duration;

/**
 * Constants for message dispatch and retry logic.
 */
public final class MessagingConstants {

  private MessagingConstants() {}

  /** Maximum retry attempts for failed message delivery. */
  public static final int MAX_RETRIES = 5;

  /** Base seconds for exponential backoff. */
  public static final long BASE_RETRY_SECONDS = 10L;

  /** Maximum backoff duration in seconds. */
  public static final long MAX_BACKOFF_SECONDS = Duration.ofHours(1).getSeconds();

  /** Default batch size for scheduler processing. */
  public static final int DISPATCH_BATCH_SIZE = 50;

  /**
   * Attachments rendered at send time (payslip PDFs) per dispatch tick, about 1 s of CPU; the
   * other referencing messages wait for a later tick without counting a retry.
   */
  public static final int MAX_RENDERS_PER_TICK = 10;
}
