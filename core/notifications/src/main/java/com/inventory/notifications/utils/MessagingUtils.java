package com.inventory.notifications.utils;

import com.inventory.notifications.constants.MessagingConstants;

/**
 * Utility methods for messaging.
 */
public final class MessagingUtils {

  private MessagingUtils() {}

  /**
   * Computes exponential backoff seconds for the given retry count.
   */
  public static long computeBackoffSeconds(int nextRetryCount) {
    long backoffSeconds = (long) (MessagingConstants.BASE_RETRY_SECONDS
        * Math.pow(2, Math.max(0, nextRetryCount - 1)));
    return Math.min(backoffSeconds, MessagingConstants.MAX_BACKOFF_SECONDS);
  }

  /**
   * Simple placeholder replacement: {{key}} -> value from variables map.
   */
  public static String resolveTemplate(String template, java.util.Map<String, Object> variables) {
    if (template == null || variables == null || variables.isEmpty()) {
      return template;
    }
    String result = template;
    for (java.util.Map.Entry<String, Object> entry : variables.entrySet()) {
      String placeholder = "{{" + entry.getKey() + "}}";
      String value = entry.getValue() != null ? entry.getValue().toString() : "";
      result = result.replace(placeholder, value);
    }
    return result;
  }
}
