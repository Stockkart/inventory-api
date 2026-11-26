package com.inventory.plan.service.refund;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.ShopCreditSource;
import com.inventory.plan.service.wallet.WalletReservationHandler;
import com.inventory.plan.service.wallet.WalletService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gives back wallet credit the order used. Spent credit is credited back (paying off any outstanding
 * clawback first); a reservation that fulfilment never spent is released.
 */
@Component
@Order(30)
public class WalletRefundStep implements OrderRefundStep {

  @Autowired
  private WalletService walletService;

  @Autowired
  private WalletReservationHandler reservations;

  @Override
  public void reverse(PlanPaymentOrder order) {
    if (order.getWalletCredit() == null || order.getWalletCredit().signum() <= 0) {
      return;
    }
    if (walletService.consumed(order.getId())) {
      walletService.credit(order.getShopId(), order.getWalletCredit(), ShopCreditSource.ORDER_REFUND,
          order.getId(), "Refund of order " + order.getId(), order.getRefundedByUserId());
    } else {
      reservations.release(order);
    }
  }
}
