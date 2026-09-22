package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.pluginengine.cart.CartLineReductionPort;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.mapper.PurchaseMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Task 4b: wiring {@link CartLineReductionPort} into {@code CheckoutService.updateCart} so a
 * cafe bill line reduced below what the kitchen was sent reaches {@code CafeKotCancelService}
 * (through the port), while every other vertical's reduction takes the path it always has.
 *
 * <p>Uses the same {@link InMemoryPurchases} stand-in as {@link CheckoutServiceCartWriteTest},
 * with a mocked {@link CartLineReductionPort} in place of the real cafe-plugin adapter -- the
 * one thing {@code core/product} is allowed to see of it.
 */
class CheckoutServiceCartLineReductionWiringTest {

  private static final String SHOP_ID = "shop-1";
  private static final String USER_ID = "user-1";
  private static final String BILL_ID = "bill-7";

  private InMemoryPurchases purchases;
  private CheckoutService checkoutService;
  private CartLineReductionPort port;

  @BeforeEach
  void setUp() {
    purchases = new InMemoryPurchases();
    checkoutService = new CheckoutService();
    port = mock(CartLineReductionPort.class);
    ShopRepository shopRepository = mock(ShopRepository.class);
    when(shopRepository.findById(anyString())).thenReturn(Optional.<Shop>empty());
    ReflectionTestUtils.setField(checkoutService, "shopRepository", shopRepository);
    ReflectionTestUtils.setField(checkoutService, "purchaseMapper", mock(PurchaseMapper.class));
    ReflectionTestUtils.setField(checkoutService, "purchaseRepository", purchases.repository());
    ReflectionTestUtils.setField(
        checkoutService,
        "purchaseTargetedWriter",
        new PurchaseTargetedWriter(purchases.mongoTemplate()));
    ReflectionTestUtils.setField(checkoutService, "cartLineReductionPort", port);
  }

  @Test
  void reducingASentCafeLineTellsTheKitchen() {
    Purchase bill = openBill();
    bill.getItems().add(menuLine("a1", "menu:tea", "Tea", 3, 3));
    Purchase cart = purchases.seed(bill);

    reduce(cart, "menu:tea", -2);

    String expectedKey = CheckoutService.cancelIdempotencyKey(BILL_ID, "a1", 3, 1);
    verify(port)
        .lineReduced(SHOP_ID, USER_ID, BILL_ID, "a1", 3, 1, expectedKey);
  }

  @Test
  void reducingAnUnsentCafeLineTellsNobody() {
    // kotSentQuantity null: the KOT tab screen never flushed this line, so nothing was ever
    // handed to the kitchen for it to cancel.
    Purchase bill = openBill();
    bill.getItems().add(menuLine("a1", "menu:tea", "Tea", 3, null));
    Purchase cart = purchases.seed(bill);

    reduce(cart, "menu:tea", -2);

    verifyNoInteractions(port);
  }

  @Test
  void reducingAMedicalLineTellsNobody() {
    // No lineRef and no kotSentQuantity: exactly how every grocery, medical and sports line
    // looks in this suite (see CheckoutServiceCartWriteTest.aLineWithNoneOfTheCafeFieldsIsStoredIdentically),
    // since the cafe fields are the only signal that distinguishes a kitchen line at all.
    Purchase bill = openBill();
    bill.getItems().add(menuLine(null, "menu:soap", "Soap", 3, null));
    Purchase cart = purchases.seed(bill);

    reduce(cart, "menu:soap", -2);

    verifyNoInteractions(port);
  }

  @Test
  void theSameLogicalEditReusesItsIdempotencyKey() {
    String first = CheckoutService.cancelIdempotencyKey(BILL_ID, "a1", 3, 1);
    String second = CheckoutService.cancelIdempotencyKey(BILL_ID, "a1", 3, 1);

    assertEquals(first, second, "a retry of the exact same edit must resolve to the same key");

    String aDifferentEdit = CheckoutService.cancelIdempotencyKey(BILL_ID, "a1", 3, 0);
    assertNotEquals(
        first, aDifferentEdit, "a genuinely different edit must not collide with an earlier one");

    // Proven end to end too: two distinct reductions on the same line produce two distinct calls.
    Purchase bill = openBill();
    bill.getItems().add(menuLine("a1", "menu:tea", "Tea", 5, 5));
    Purchase cart = purchases.seed(bill);

    cart = reduce(cart, "menu:tea", -2);
    reduce(cart, "menu:tea", -1);

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(port, times(2))
        .lineReduced(eq(SHOP_ID), eq(USER_ID), eq(BILL_ID), eq("a1"), anyInt(), anyInt(), keys.capture());
    assertNotEquals(
        keys.getAllValues().get(0),
        keys.getAllValues().get(1),
        "5->3 and 3->2 are different edits and must not share a key");
  }

  // ------------------------------------------------------------------- helpers

  private Purchase reduce(Purchase cart, String sellableRef, int delta) {
    return checkoutService.updateCart(
        cart,
        purchases.stored(BILL_ID),
        List.of(decrementLine(sellableRef, delta)),
        null,
        null,
        null,
        BillingMode.REGULAR);
  }

  private static PurchaseItem decrementLine(String sellableRef, int delta) {
    PurchaseItem item = new PurchaseItem();
    item.setSellableRef(sellableRef);
    item.setSellMode("menu");
    item.setBaseQuantity(delta);
    item.setQuantity(BigDecimal.valueOf(delta));
    return item;
  }

  private static PurchaseItem menuLine(
      String lineRef, String sellableRef, String name, int baseQuantity, Integer kotSentQuantity) {
    PurchaseItem item = new PurchaseItem();
    item.setLineRef(lineRef);
    item.setSellableRef(sellableRef);
    item.setSellMode("menu");
    item.setName(name);
    item.setBaseQuantity(baseQuantity);
    item.setQuantity(BigDecimal.valueOf(baseQuantity));
    item.setSaleUnit("UNIT");
    item.setPriceToRetail(BigDecimal.valueOf(30));
    item.setMaximumRetailPrice(BigDecimal.valueOf(30));
    item.setTotalAmount(BigDecimal.valueOf(30).multiply(BigDecimal.valueOf(baseQuantity)));
    item.setKotSentQuantity(kotSentQuantity);
    return item;
  }

  private static Purchase openBill() {
    Purchase bill = new Purchase();
    bill.setId(BILL_ID);
    bill.setShopId(SHOP_ID);
    bill.setUserId(USER_ID);
    bill.setStatus(PurchaseStatus.CREATED);
    bill.setBillingMode(BillingMode.REGULAR);
    bill.setItems(new ArrayList<>());
    bill.setCreatedAt(Instant.now());
    bill.setUpdatedAt(Instant.now());
    return bill;
  }
}
