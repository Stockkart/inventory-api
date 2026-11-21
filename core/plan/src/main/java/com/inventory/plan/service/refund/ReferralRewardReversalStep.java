package com.inventory.plan.service.refund;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.service.referral.ReferralRewardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** The referrer's reward for this order: voided if not yet credited, clawed back if it was (§24). */
@Component
@Order(40)
public class ReferralRewardReversalStep implements OrderRefundStep {

  static final String REASON = "ORDER_REFUNDED";

  @Autowired
  private ReferralRewardService rewardService;

  @Override
  public void reverse(PlanPaymentOrder order) {
    rewardService.reverseForOrder(order.getId(), REASON, order.getRefundedByUserId());
  }
}
