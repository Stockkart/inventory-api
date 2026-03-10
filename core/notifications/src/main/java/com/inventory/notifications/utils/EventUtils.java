package com.inventory.notifications.utils;

import com.inventory.notifications.utils.constants.EventConstants;

/**
 * Utility methods for event-related computations.
 */
public final class EventUtils {

  private EventUtils() {}

  /**
   * Computes exponential backoff seconds for the given retry count.
   * Capped at {@link EventConstants#MAX_BACKOFF_SECONDS}.
   */
  public static long computeBackoffSeconds(int nextRetryCount) {
    long backoffSeconds = (long) (EventConstants.BASE_RETRY_SECONDS
        * Math.pow(2, Math.max(0, nextRetryCount - 1)));
    return Math.min(backoffSeconds, EventConstants.MAX_BACKOFF_SECONDS);
  }
}
