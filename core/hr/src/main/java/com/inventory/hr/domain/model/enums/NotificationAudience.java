package com.inventory.hr.domain.model.enums;

/** USER rows go to one requester; APPROVERS rows to whoever holds APPROVE_LEAVE when reading. */
public enum NotificationAudience {
  USER,
  APPROVERS
}
