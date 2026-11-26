package com.inventory.hr.domain.model.enums;

/**
 * USER rows go to one person; APPROVERS rows to whoever may decide that kind of request when
 * reading.
 */
public enum NotificationAudience {
  USER,
  APPROVERS
}
