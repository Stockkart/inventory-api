package com.inventory.hr.service;

import org.springframework.util.StringUtils;

/**
 * The only place that words a worker-app notification (worker-app R4.13, R10.3). Every builder
 * takes names, the shop name and the rejection reason, and nothing else, so a text can never carry
 * a phone number, an e-mail address, pay or a document: the caller has no parameter to put them
 * in. {@code JOIN_REQUEST_RECEIVED} rows name the worker only; the worker-facing rows name the shop.
 */
public final class WorkerNotificationTexts {

  private WorkerNotificationTexts() {}

  /** To the approvers: a join request was filed. */
  public static String joinRequestReceived(String workerName) {
    return workerName + " asked to join";
  }

  /** To the worker: {@code JOIN_REQUEST_APPROVED}, whether the approval created or linked (R10.2). */
  public static String joinRequestApproved(String shopName) {
    return shopName + " accepted you";
  }

  /** To the worker: {@code JOIN_REQUEST_REJECTED}; the reason (already trimmed) when given. */
  public static String joinRequestRejected(String shopName, String reason) {
    return withReason(shopName + " did not accept your request", reason);
  }

  /** To the worker: {@code PROFILE_REQUESTED} (worker-app R8.7). */
  public static String profileRequested(String shopName) {
    return shopName + " asked for your profile details";
  }

  private static String withReason(String sentence, String reason) {
    return StringUtils.hasText(reason) ? sentence + ": " + reason.trim() : sentence;
  }
}
