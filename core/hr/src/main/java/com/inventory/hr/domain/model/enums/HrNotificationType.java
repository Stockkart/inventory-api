package com.inventory.hr.domain.model.enums;

/** In-app HR notification type (R6.9; worker-app R10.2). */
public enum HrNotificationType {
  LEAVE_REQUESTED,
  LEAVE_APPROVED,
  LEAVE_REJECTED,
  /** To the shop's approvers: a worker asked to join (text: worker's name only). */
  JOIN_REQUEST_RECEIVED,
  /** To the worker: a join request was approved, created or linked (text: shop name; R10.2). */
  JOIN_REQUEST_APPROVED,
  /** To the worker: a join request was rejected (text: shop name and the reason, if any). */
  JOIN_REQUEST_REJECTED,
  /** To the worker: the shop asked for their profile details (worker-app R8.7). */
  PROFILE_REQUESTED
}
