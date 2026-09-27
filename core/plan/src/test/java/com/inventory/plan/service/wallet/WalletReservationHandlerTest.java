package com.inventory.plan.service.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PricedCart;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletReservationHandlerTest {

  @Mock private WalletService walletService;

  @InjectMocks
  private WalletReservationHandler handler;

  private static PlanPaymentOrder order(String walletCredit) {
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId("order-1");
    order.setShopId("shop-1");
    order.setWalletCredit(walletCredit == null ? null : new BigDecimal(walletCredit));
    return order;
  }

  private static PricedCart cart(String walletCredit) {
    return new PricedCart(null, List.of(), BigDecimal.TEN, BigDecimal.ZERO, new BigDecimal(walletCredit),
        BigDecimal.ZERO, 12);
  }

  @Test
  void ordersWithoutWalletCreditTouchNothing() {
    handler.reserve(order("0"), cart("0"));
    handler.release(order(null));
    handler.consume(order("0"));
    assertThat(handler.reacquire(order("0"))).isTrue();
    verifyNoInteractions(walletService);
  }

  @Test
  void aBalanceThatDroppedSinceTheQuoteRejectsCheckout() {
    when(walletService.reserve("shop-1", "order-1", new BigDecimal("200"))).thenReturn(false);

    assertThatThrownBy(() -> handler.reserve(order("200"), cart("200")))
        .isInstanceOfSatisfying(BaseException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WALLET_BALANCE_CHANGED));
  }

  @Test
  void releaseAndConsumeUseTheOrdersAmount() {
    handler.release(order("200"));
    handler.consume(order("200"));

    verify(walletService).release("shop-1", "order-1", new BigDecimal("200"));
    verify(walletService).consume("shop-1", "order-1", new BigDecimal("200"));
  }

  @Test
  void reacquireReportsWhetherTheCreditIsStillThere() {
    when(walletService.reacquire(any(), any(), any())).thenReturn(false);
    assertThat(handler.reacquire(order("200"))).isFalse();
  }
}
