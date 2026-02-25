package com.inventory.notifications.exception;

/**
 * Exception thrown when a notification send operation fails.
 */
public class NotificationException extends Exception {

  public NotificationException(String message) {
    super(message);
  }

  public NotificationException(String message, Throwable cause) {
    super(message, cause);
  }
}
