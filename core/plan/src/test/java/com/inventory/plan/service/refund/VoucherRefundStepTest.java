package com.inventory.plan.service.refund;

import static org.mockito.Mockito.verify;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.service.voucher.VoucherReservationHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoucherRefundStepTest {

  @Mock private VoucherReservationHandler voucherReservationHandler;

  @InjectMocks
  private VoucherRefundStep step;

  @Test
  void aRefundReleasesTheSlotsTheOrderStillReserves() {
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId("order-1");

    step.reverse(order);

    // release() changes RESERVED rows only, so redeemed slots stay used
    verify(voucherReservationHandler).release(order);
  }
}
