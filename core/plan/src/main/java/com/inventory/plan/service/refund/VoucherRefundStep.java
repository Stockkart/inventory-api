package com.inventory.plan.service.refund;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.service.voucher.VoucherReservationHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Frees voucher slots the order still only reserves (fulfilment never redeemed them), so a refunded
 * order does not hold a slot forever. Redeemed slots stay used: release touches RESERVED rows only.
 */
@Component
@Order(20)
public class VoucherRefundStep implements OrderRefundStep {

  @Autowired
  private VoucherReservationHandler voucherReservationHandler;

  @Override
  public void reverse(PlanPaymentOrder order) {
    voucherReservationHandler.release(order);
  }
}
