package com.inventory.plan.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.plan.utils.constants.PlanPaymentConstants;
import org.junit.jupiter.api.Test;

class OrderPhaseTest {

  @Test
  void everyOrderStatusHasTheBuyersMeaning() {
    assertThat(OrderPhase.of(PlanPaymentConstants.STATUS_CREATED)).isEqualTo(OrderPhase.AWAITING_PAYMENT);
    assertThat(OrderPhase.of(PlanPaymentConstants.STATUS_PAYMENT_PENDING)).isEqualTo(OrderPhase.AWAITING_PAYMENT);
    assertThat(OrderPhase.of(PlanPaymentConstants.STATUS_PAID)).isEqualTo(OrderPhase.PROCESSING);
    assertThat(OrderPhase.of(PlanPaymentConstants.STATUS_FULFILLING)).isEqualTo(OrderPhase.PROCESSING);
    assertThat(OrderPhase.of(PlanPaymentConstants.STATUS_FULFILMENT_FAILED)).isEqualTo(OrderPhase.PROCESSING);
    assertThat(OrderPhase.of(PlanPaymentConstants.STATUS_FULFILLED)).isEqualTo(OrderPhase.FULFILLED);
    assertThat(OrderPhase.of(PlanPaymentConstants.STATUS_PAYMENT_FAILED)).isEqualTo(OrderPhase.FAILED);
    assertThat(OrderPhase.of(PlanPaymentConstants.STATUS_EXPIRED)).isEqualTo(OrderPhase.ENDED);
    assertThat(OrderPhase.of(PlanPaymentConstants.STATUS_CANCELLED)).isEqualTo(OrderPhase.ENDED);
  }
}
