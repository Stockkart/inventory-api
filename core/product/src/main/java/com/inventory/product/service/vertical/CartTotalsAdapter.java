package com.inventory.product.service.vertical;

import com.inventory.pluginengine.cart.CartTotalsPort;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.service.CheckoutService;
import com.inventory.product.utils.CheckoutUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The one implementation of {@link CartTotalsPort}: it hands the request straight to the
 * arithmetic the add-to-cart path uses, so a bill a plugin appended to carries exactly the
 * numbers the next add-to-cart would compute for the same lines.
 *
 * <p>No plugin calls it today. Its caller was the retired tab flush, which appended priced lines
 * to a bill behind the checkout path; a punch appends no lines — the Sell cart already holds
 * them, and the add-to-cart path has already totalled them — so it needs no recompute. Kept
 * because it is the only correct way for a future appender to repair the money it displaces.
 *
 * <p>Only an open cart is recomputed. A bill that has since been COMPLETED has an invoice number
 * and a settled amount against it, and recomputing that from lines would rewrite a document the
 * money has already been taken for.
 *
 * <p><b>Why the write is a targeted {@code $set} and not {@code save(cart)}.</b> The arithmetic
 * runs on a snapshot read a moment earlier. Saving the whole document would write {@code items}
 * as <i>this</i> caller read them, and anything another writer appended in between — a
 * {@code cafeKotPunches} append, a {@code cafeKotCancels} push, an
 * {@code items.$.kotSentQuantity} advance or decrement, an ordinary add-to-cart from the Sell
 * screen — would be deleted by it. That is a lost update, and for a punch it means the record of
 * what the kitchen was already sent vanishing from the bill: the next press of Print KOT sends
 * the same food again. Being <i>mapped</i> stops those fields
 * being silently dropped when something else saves the document; it does nothing about this.
 *
 * <p>So only the money fields are written, by field, under a query that still requires the cart to
 * be open. There is no {@code MongoTransactionManager} in this codebase, so an optimistic
 * {@code @Version} on {@link Purchase} would hand a conflict to every existing writer of the
 * document with no transaction to retry inside; a {@code $set} of the totals cannot delete
 * anything. It is still racy — two recomputes interleaved leave the totals of whichever wrote
 * last, which may be one round stale — but a stale total is repaired by the next recompute or the
 * next add-to-cart, whereas a deleted line is gone for good.
 *
 * <p>The line-level {@code totalAmount} that {@code applyCartTotals} recomputes on the snapshot is
 * deliberately not written back, for the same reason: those items belong to whoever wrote them,
 * and each writer already stores its own line total through the same calculator.
 */
@Component
@Slf4j
public class CartTotalsAdapter implements CartTotalsPort {

  private final PurchaseRepository purchaseRepository;
  private final CheckoutService checkoutService;
  private final MongoTemplate mongoTemplate;

  public CartTotalsAdapter(
      PurchaseRepository purchaseRepository,
      CheckoutService checkoutService,
      MongoTemplate mongoTemplate) {
    this.purchaseRepository = purchaseRepository;
    this.checkoutService = checkoutService;
    this.mongoTemplate = mongoTemplate;
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

    if (storeTotals(shopId, purchaseId, cart) == 0) {
      // The cart was settled between the read and this write. What it settled for is what it held
      // at completion, and overwriting that is exactly what the status clause exists to prevent.
      log.warn(
          "Cart {} in shop {} was no longer an open cart when its recomputed totals were written",
          purchaseId,
          shopId);
      return;
    }
    log.info(
        "Recomputed totals for cart {} in shop {}: grandTotal {}",
        purchaseId,
        shopId,
        cart.getGrandTotal());
  }

  /** The money fields, and only those — never {@code items}, never the whole document. */
  private long storeTotals(String shopId, String purchaseId, Purchase cart) {
    Query query =
        Query.query(Criteria.where("_id").is(purchaseId).and("shopId").is(shopId))
            // Still open at the moment of the write, not merely at the moment of the read. A bill
            // with no status at all is an open one, as the read above also treats it.
            .addCriteria(
                new Criteria()
                    .orOperator(
                        Criteria.where("status").is(PurchaseStatus.CREATED),
                        Criteria.where("status").exists(false)));
    Update update =
        new Update()
            .set("subTotal", cart.getSubTotal())
            .set("taxTotal", cart.getTaxTotal())
            .set("sgstAmount", cart.getSgstAmount())
            .set("cgstAmount", cart.getCgstAmount())
            .set("discountTotal", cart.getDiscountTotal())
            .set("saleAdditionalDiscountTotal", cart.getSaleAdditionalDiscountTotal())
            .set("grandTotal", cart.getGrandTotal())
            .set("totalCost", cart.getTotalCost())
            .set("revenueBeforeTax", cart.getRevenueBeforeTax())
            .set("revenueAfterTax", cart.getRevenueAfterTax())
            .set("totalProfit", cart.getTotalProfit())
            .set("marginPercent", cart.getMarginPercent())
            .set("updatedAt", Instant.now());
    return mongoTemplate.updateFirst(query, update, Purchase.class).getMatchedCount();
  }
}
