package com.inventory.plan.service.refund;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.ShopCreditSource;
import com.inventory.plan.service.wallet.WalletReservationHandler;
import com.inventory.plan.service.wallet.WalletService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletRefundStepTest {

  @Mock private WalletService walletService;
  @Mock private WalletReservationHandler reservations;

  @InjectMocks
  private WalletRefundStep step;

  private static PlanPaymentOrder order(String walletCredit) {
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId("order-1");
    order.setShopId("shop-1");
    order.setWalletCredit(walletCredit == null ? null : new BigDecimal(walletCredit));
    return order;
  }

  @Test
  void spentCreditIsCreditedBack() {
    PlanPaymentOrder order = order("500.00");
    when(walletService.consumed("order-1")).thenReturn(true);

    step.reverse(order);

    verify(walletService).credit(eq("shop-1"), eq(new BigDecimal("500.00")), eq(ShopCreditSource.ORDER_REFUND),
        eq("order-1"), anyString(), any());
    verify(reservations, never()).release(any());
  }

  @Test
  void anUnspentReservationIsReleased() {
    PlanPaymentOrder order = order("500.00");
    when(walletService.consumed("order-1")).thenReturn(false);

    step.reverse(order);

    verify(reservations).release(order);
  }

  @Test
  void ordersWithoutWalletCreditAreSkipped() {
    step.reverse(order(null));

    verifyNoInteractions(walletService, reservations);
  }
}
