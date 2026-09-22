package com.inventory.plugins.cafe;

import com.inventory.pluginengine.cart.CartLineReductionPort;
import org.springframework.stereotype.Component;

/**
 * The one implementation of {@link CartLineReductionPort}: hands the reduction straight to
 * {@link CafeKotCancelService}, which already knows how to turn "quantity went down" into
 * "owed to the kitchen, or not" from fields on the bill line itself.
 *
 * <p>This class adds nothing of its own on purpose — see the port's javadoc for why it is this
 * narrow. It is the mirror image of {@code CartTotalsAdapter} in {@code core/product}: there, the
 * interface lives in {@code pluginengine} and the one implementation sits in {@code core/product}
 * because only {@code core/product} can see the arithmetic it needs; here, the interface is the
 * same kind of narrow contract but the implementation sits in {@code plugins/cafe} because only
 * this plugin can see the kitchen-ticket domain it needs.
 */
@Component
public class CartLineReductionAdapter implements CartLineReductionPort {

  private final CafeKotCancelService cafeKotCancelService;

  public CartLineReductionAdapter(CafeKotCancelService cafeKotCancelService) {
    this.cafeKotCancelService = cafeKotCancelService;
  }

  @Override
  public void lineReduced(
      String shopId,
      String userId,
      String purchaseId,
      String lineRef,
      int fromQty,
      int toQty,
      String idempotencyKey) {
    // The tickets are not the checkout path's concern -- only that the kitchen was told. A
    // caller that needs them (the KOT screen) reaches CafeKotCancelService through CafeKotPort
    // instead, which returns them.
    cafeKotCancelService.cancel(shopId, userId, purchaseId, lineRef, fromQty, toQty, idempotencyKey);
  }
}
