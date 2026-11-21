package com.inventory.plan.domain.model;

import com.inventory.plan.utils.constants.PlanPaymentConstants;

/**
 * What a plan order's status means for the buyer, so apps never derive it from status names.
 */
public enum OrderPhase {
  /** CREATED or PAYMENT_PENDING: the customer can still pay. */
  AWAITING_PAYMENT,
  /**
   * PAID, FULFILLING or FULFILMENT_FAILED: payment received, the plan is still being applied (an
   * operator finishes a FULFILMENT_FAILED order). Show "payment received, confirming".
   */
  PROCESSING,
  /** FULFILLED: the plan and everything bought with it are active. */
  FULFILLED,
  /** PAYMENT_FAILED: no payment was taken. */
  FAILED,
  /** EXPIRED or CANCELLED: ended without payment. */
  ENDED;

  public static OrderPhase of(String status) {
    if (status == null) {
      return ENDED;
    }
    return switch (status) {
      case PlanPaymentConstants.STATUS_CREATED, PlanPaymentConstants.STATUS_PAYMENT_PENDING -> AWAITING_PAYMENT;
      case PlanPaymentConstants.STATUS_PAID, PlanPaymentConstants.STATUS_FULFILLING,
          PlanPaymentConstants.STATUS_FULFILMENT_FAILED -> PROCESSING;
      case PlanPaymentConstants.STATUS_FULFILLED -> FULFILLED;
      case PlanPaymentConstants.STATUS_PAYMENT_FAILED -> FAILED;
      default -> ENDED;
    };
  }
}
