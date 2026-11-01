package com.inventory.plan.service.wallet;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PricedCart;
import com.inventory.plan.service.order.OrderReservationHandler;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Wallet credit an order uses (r4.3): reserved at checkout, spent at fulfilment, given back when the
 * order ends unpaid. Never debited at quote time, so an abandoned checkout costs nothing.
 */
@Component
public class WalletReservationHandler implements OrderReservationHandler {

  @Autowired
  private WalletService walletService;

  @Override
  public void reserve(PlanPaymentOrder order, PricedCart cart) {
    if (isPositive(cart.walletCredit())
        && !walletService.reserve(order.getShopId(), order.getId(), cart.walletCredit())) {
      throw new BaseException(ErrorCode.WALLET_BALANCE_CHANGED,
          "Your wallet balance changed since the quote. Refresh and try again.");
    }
  }

  @Override
  public void release(PlanPaymentOrder order) {
    if (isPositive(order.getWalletCredit())) {
      walletService.release(order.getShopId(), order.getId(), order.getWalletCredit());
    }
  }

  @Override
  public boolean reacquire(PlanPaymentOrder order) {
    return !isPositive(order.getWalletCredit())
        || walletService.reacquire(order.getShopId(), order.getId(), order.getWalletCredit());
  }

  public void consume(PlanPaymentOrder order) {
    if (isPositive(order.getWalletCredit())) {
      walletService.consume(order.getShopId(), order.getId(), order.getWalletCredit());
    }
  }

  private static boolean isPositive(BigDecimal amount) {
    return amount != null && amount.signum() > 0;
  }
}
