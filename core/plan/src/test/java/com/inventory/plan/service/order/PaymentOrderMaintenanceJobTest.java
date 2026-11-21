package com.inventory.plan.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Expiry pages until a short page, and releases only what it actually ended. */
@ExtendWith(MockitoExtension.class)
class PaymentOrderMaintenanceJobTest {

  private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

  @Mock private PaymentOrderStateService stateService;
  @Mock private OrderReservations reservations;
  @Mock private OrderFulfilmentService fulfilmentService;
  @InjectMocks private PaymentOrderMaintenanceJob job;

  @BeforeEach
  void setUp() {
    job.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  void expiryKeepsPagingUntilAShortPage() {
    when(stateService.findExpirable(eq(NOW), any(), anyInt()))
        .thenReturn(orders(PaymentOrderMaintenanceJob.BATCH), orders(3));
    when(stateService.endUnpaid(anyString(), eq(PlanPaymentConstants.STATUS_EXPIRED), any(), eq(NOW)))
        .thenReturn(true);

    assertThat(job.expireUnpaid()).isEqualTo(PaymentOrderMaintenanceJob.BATCH + 3);

    verify(stateService, times(2)).findExpirable(eq(NOW), any(), anyInt());
    verify(reservations, times(PaymentOrderMaintenanceJob.BATCH + 3)).releaseEnded(any(), eq(NOW));
  }

  @Test
  void anOrderPaidBeforeTheJobEndedItIsNotReleased() {
    when(stateService.findExpirable(eq(NOW), any(), anyInt())).thenReturn(orders(1));
    when(stateService.endUnpaid(anyString(), any(), any(), any())).thenReturn(false);

    assertThat(job.expireUnpaid()).isZero();

    verify(reservations, never()).releaseEnded(any(), any());
    verify(reservations, never()).release(any());
  }

  private static List<PlanPaymentOrder> orders(int n) {
    return new ArrayList<>(IntStream.range(0, n).mapToObj(i -> {
      PlanPaymentOrder o = new PlanPaymentOrder();
      o.setId("o" + i);
      return o;
    }).toList());
  }
}
