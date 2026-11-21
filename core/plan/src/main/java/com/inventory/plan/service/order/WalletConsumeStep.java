package com.inventory.plan.service.order;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.service.wallet.WalletReservationHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Spends the wallet credit the order reserved at checkout. */
@Component
@Order(30)
public class WalletConsumeStep implements OrderFulfilmentStep {

  @Autowired
  private WalletReservationHandler wallet;

  @Override
  public void apply(PlanPaymentOrder order) {
    wallet.consume(order);
  }
}
