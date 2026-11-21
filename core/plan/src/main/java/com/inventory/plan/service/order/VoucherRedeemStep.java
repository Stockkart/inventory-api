package com.inventory.plan.service.order;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.service.voucher.VoucherReservationHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Turns the order's voucher reservations into redemptions. */
@Component
@Order(20)
public class VoucherRedeemStep implements OrderFulfilmentStep {

  @Autowired
  private VoucherReservationHandler vouchers;

  @Override
  public void apply(PlanPaymentOrder order) {
    vouchers.redeem(order);
  }
}
