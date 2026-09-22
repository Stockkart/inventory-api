package com.inventory.product.service.vertical;

import com.inventory.pluginengine.cart.CartTotalsPort;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.service.CheckoutService;
import com.inventory.product.utils.CheckoutUtils;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The one implementation of {@link CartTotalsPort}: it hands the request straight to the
 * arithmetic the add-to-cart path uses, so a bill a cafe flush appended to carries exactly the
 * numbers the next add-to-cart would compute for the same lines.
 *
 * <p>Only an open cart is recomputed. A bill that has since been COMPLETED has an invoice number
 * and a settled amount against it, and recomputing that from lines would rewrite a document the
 * money has already been taken for.
 */
@Component
@Slf4j
public class CartTotalsAdapter implements CartTotalsPort {

  private final PurchaseRepository purchaseRepository;
  private final CheckoutService checkoutService;

  public CartTotalsAdapter(PurchaseRepository purchaseRepository, CheckoutService checkoutService) {
    this.purchaseRepository = purchaseRepository;
    this.checkoutService = checkoutService;
  }

  @Override
  public void recalculateTotals(String shopId, String purchaseId) {
    if (!StringUtils.hasText(shopId) || !StringUtils.hasText(purchaseId)) {
      return;
    }
    Purchase cart = purchaseRepository.findById(purchaseId).orElse(null);
    if (cart == null || !shopId.equals(cart.getShopId())) {
      log.warn("No cart {} in shop {} to recompute totals for", purchaseId, shopId);
      return;
    }
    if (cart.getStatus() != null && cart.getStatus() != PurchaseStatus.CREATED) {
      log.warn(
          "Cart {} in shop {} is {}, not an open cart; totals left as settled",
          purchaseId,
          shopId,
          cart.getStatus());
      return;
    }

    List<PurchaseItem> items =
        cart.getItems() == null ? new ArrayList<>() : new ArrayList<>(cart.getItems());
    checkoutService.applyCartTotals(
        cart, items, CheckoutUtils.normalizeBillingMode(cart.getBillingMode()));
    purchaseRepository.save(cart);
    log.info(
        "Recomputed totals for cart {} in shop {}: grandTotal {}",
        purchaseId,
        shopId,
        cart.getGrandTotal());
  }
}
