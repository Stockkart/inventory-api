package com.inventory.plan.service.order;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.service.referral.ReferralRewardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Records the referrer's reward. Runs last, and a failure here is logged for an operator rather than
 * failing an order the customer has already been granted.
 */
@Slf4j
@Component
@Order(40)
public class ReferralRewardStep implements OrderFulfilmentStep {

  @Autowired
  private ReferralRewardService rewardService;

  @Override
  public void apply(PlanPaymentOrder order) {
    try {
      rewardService.recordForOrder(order);
    } catch (RuntimeException e) {
      log.error("Recording the referral reward for order {} failed and needs an operator: {}",
          order.getId(), e.getMessage(), e);
    }
  }
}
