package com.inventory.notifications.domain.model;

/**
 * Status of an outbound message in the queue.
 */
public enum MessageStatus {
  PENDING,
  SENT,
  FAILED,
  EXHAUSTED
}
