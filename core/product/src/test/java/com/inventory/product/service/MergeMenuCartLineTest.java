package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.mapper.PurchaseMapper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code mergeMenuCartLine} is a private instance method on {@link CheckoutService}, made
 * package-private for this test rather than public. The instance is built with plain {@code
 * new} (the class has no explicit constructor) and its {@code purchaseMapper} field is a
 * Mockito mock injected via reflection, since the non-zero merge branch calls into it for
 * margin enrichment.
 */
class MergeMenuCartLineTest {

  private CheckoutService checkoutService;

  @BeforeEach
  void setUp() throws Exception {
    checkoutService = new CheckoutService();
    Field purchaseMapperField = CheckoutService.class.getDeclaredField("purchaseMapper");
    purchaseMapperField.setAccessible(true);
    purchaseMapperField.set(checkoutService, mock(PurchaseMapper.class));
  }

  private static PurchaseItem line(String ref, int baseQuantity, Integer punched) {
    PurchaseItem item = new PurchaseItem();
    item.setSellableRef(ref);
    item.setBaseQuantity(baseQuantity);
    item.setKotSentQuantity(punched);
    return item;
  }

  @Test
  void anUnpunchedLineIsStillRemovedWhenItReachesZero() {
    List<PurchaseItem> cart = new ArrayList<>(List.of(line("menu:m1", 1, null)));

    checkoutService.mergeMenuCartLine(cart, line("menu:m1", -1, null));

    assertTrue(cart.isEmpty());
  }

  @Test
  void aPunchedLineIsKeptAtZeroSoItsCancellationCanBeComputed() {
    List<PurchaseItem> cart = new ArrayList<>(List.of(line("menu:m1", 3, 3)));

    checkoutService.mergeMenuCartLine(cart, line("menu:m1", -3, null));

    // Deleting it would strand the kitchen with 3 portions nobody will bill or cancel.
    assertEquals(1, cart.size());
    assertEquals(0, cart.get(0).getBaseQuantity());
    assertEquals(3, cart.get(0).getKotSentQuantity());
  }

  @Test
  void aPunchedLineReducedButNotEmptiedKeepsItsPunchedCount() {
    List<PurchaseItem> cart = new ArrayList<>(List.of(line("menu:m1", 3, 3)));

    checkoutService.mergeMenuCartLine(cart, line("menu:m1", -2, null));

    assertEquals(1, cart.get(0).getBaseQuantity());
    assertEquals(3, cart.get(0).getKotSentQuantity());
  }

  @Test
  void aLineAlreadyReconciledToZeroIsSwept() {
    List<PurchaseItem> cart = new ArrayList<>(List.of(line("menu:m1", 0, 0)));

    checkoutService.mergeMenuCartLine(cart, line("menu:m1", 0, null));

    assertTrue(cart.isEmpty());
  }
}
