package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.integration.ShopMenuLookup;
import com.inventory.pluginengine.order.PunchCommand;
import com.inventory.pluginengine.order.PunchLine;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotStatus;
import com.inventory.plugins.cafe.domain.CafeOrder;
import com.inventory.plugins.cafe.domain.CafeOrderStatus;
import com.inventory.plugins.cafe.repository.CafeKotRepository;
import com.inventory.plugins.cafe.repository.CafeOrderPunchRepository;
import com.inventory.plugins.cafe.repository.CafeOrderRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle matrix, plus the structural shop isolation every lookup depends on.
 *
 * <pre>
 * Operation | OPEN   | BILLED           | CANCELLED
 * Punch     | accept | reject           | reject
 * Settle    | accept | return existing  | reject      (asserted at the service layer)
 * Cancel    | accept | reject           | reject
 *
 * Operation | ISSUED | VOIDED
 * Reprint   | accept | reject
 * Void      | accept | reject
 * Document  | accept | accept, stamped CANCELLED
 * </pre>
 */
class CafeRunningOrderStoreTransitionTest {

  private CafeOrderRepository orderRepository;
  private CafeKotRepository kotRepository;
  private CafeOrderPunchRepository punchRepository;
  private CafeRunningOrderStore store;
  private CafeOrder order;

  @BeforeEach
  void setUp() {
    orderRepository = mock(CafeOrderRepository.class);
    kotRepository = mock(CafeKotRepository.class);
    punchRepository = mock(CafeOrderPunchRepository.class);
    store =
        new CafeRunningOrderStore(
            orderRepository,
            kotRepository,
            punchRepository,
            mock(CafeSequenceService.class),
            mock(ShopMenuLookup.class));

    order = new CafeOrder();
    order.setId("order-1");
    order.setShopId("shop-1");
    order.setBusinessDate("2026-09-20");

    when(orderRepository.findByIdAndShopId("order-1", "shop-1")).thenReturn(Optional.of(order));
    when(punchRepository.findByShopIdAndIdempotencyKey(anyString(), anyString()))
        .thenReturn(Optional.empty());
  }

  private PunchCommand punch(String shopId) {
    return PunchCommand.builder()
        .shopId(shopId)
        .userId("user-1")
        .orderId("order-1")
        .idempotencyKey("key-1")
        .lines(List.of(PunchLine.builder().sellableRef("menu:m1").quantity(1).build()))
        .build();
  }

  // --- order lifecycle ---

  @Test
  void punchRejectedOnBilledOrder() {
    order.setStatus(CafeOrderStatus.BILLED);
    assertThrows(ValidationException.class, () -> store.punch(punch("shop-1")));
  }

  @Test
  void punchRejectedOnCancelledOrder() {
    order.setStatus(CafeOrderStatus.CANCELLED);
    assertThrows(ValidationException.class, () -> store.punch(punch("shop-1")));
  }

  @Test
  void cancelRejectedOnCancelledOrder() {
    order.setStatus(CafeOrderStatus.CANCELLED);
    assertThrows(
        ValidationException.class,
        () -> store.cancelOrder("shop-1", "user-1", "order-1", "reason"));
  }

  @Test
  void cancelRejectedOnBilledOrder() {
    order.setStatus(CafeOrderStatus.BILLED);
    assertThrows(
        ValidationException.class,
        () -> store.cancelOrder("shop-1", "user-1", "order-1", "reason"));
  }

  @Test
  void bindPurchaseRejectedOnBilledOrder() {
    order.setStatus(CafeOrderStatus.BILLED);
    assertThrows(
        ValidationException.class, () -> store.bindPurchase("shop-1", "order-1", "purchase-1"));
  }

  @Test
  void markBilledRejectedOnCancelledOrder() {
    order.setStatus(CafeOrderStatus.CANCELLED);
    assertThrows(
        ValidationException.class, () -> store.markBilled("shop-1", "user-1", "order-1"));
  }

  // --- ticket lifecycle ---

  @Test
  void voidedTicketIsStillReadableForAudit() {
    CafeKot voided = voidedKot();
    when(kotRepository.findByIdAndShopId("kot-1", "shop-1")).thenReturn(Optional.of(voided));

    assertEquals("VOIDED", store.findKot("shop-1", "kot-1").orElseThrow().getStatus());
    assertEquals("table left", store.findKot("shop-1", "kot-1").orElseThrow().getVoidReason());
  }

  @Test
  void reprintRejectedOnVoidedTicket() {
    when(kotRepository.findByIdAndShopId("kot-1", "shop-1")).thenReturn(Optional.of(voidedKot()));

    assertThrows(ValidationException.class, () -> store.markReprinted("shop-1", "kot-1"));
  }

  @Test
  void voidRejectedOnVoidedTicket() {
    when(kotRepository.findByIdAndShopId("kot-1", "shop-1")).thenReturn(Optional.of(voidedKot()));

    assertThrows(
        ValidationException.class,
        () -> store.voidLines("shop-1", "user-1", "kot-1", List.of("l1"), "reason"));
  }

  private CafeKot voidedKot() {
    CafeKot kot = new CafeKot();
    kot.setId("kot-1");
    kot.setShopId("shop-1");
    kot.setOrderId("order-1");
    kot.setStatus(CafeKotStatus.VOIDED);
    kot.setVoidReason("table left");
    return kot;
  }

  // --- shop isolation ---
  //
  // Isolation is structural: every repository method is ...AndShopId, so another shop's id simply
  // does not resolve. These tests exist to keep it that way — swapping in findById() would make
  // every other test in the suite still pass.

  @Test
  void punchForAnotherShopDoesNotResolveTheOrder() {
    order.setStatus(CafeOrderStatus.OPEN);
    when(orderRepository.findByIdAndShopId("order-1", "shop-B")).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> store.punch(punch("shop-B")));
    verify(orderRepository, never()).findById(anyString());
  }

  @Test
  void findOrderForAnotherShopReturnsEmpty() {
    when(orderRepository.findByIdAndShopId("order-1", "shop-B")).thenReturn(Optional.empty());

    assertTrue(store.findOrder("shop-B", "order-1").isEmpty());
    verify(orderRepository, never()).findById(anyString());
  }

  @Test
  void findKotForAnotherShopReturnsEmpty() {
    when(kotRepository.findByIdAndShopId("kot-1", "shop-B")).thenReturn(Optional.empty());

    assertTrue(store.findKot("shop-B", "kot-1").isEmpty());
    verify(kotRepository, never()).findById(anyString());
  }

  @Test
  void voidForAnotherShopDoesNotResolveTheTicket() {
    when(kotRepository.findByIdAndShopId("kot-1", "shop-B")).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () -> store.voidLines("shop-B", "user-1", "kot-1", List.of("l1"), "reason"));
    verify(kotRepository, never()).findById(anyString());
  }

  @Test
  void reprintForAnotherShopDoesNotResolveTheTicket() {
    when(kotRepository.findByIdAndShopId("kot-1", "shop-B")).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class, () -> store.markReprinted("shop-B", "kot-1"));
    verify(kotRepository, never()).findById(anyString());
  }

  @Test
  void cancelForAnotherShopDoesNotResolveTheOrder() {
    when(orderRepository.findByIdAndShopId("order-1", "shop-B")).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () -> store.cancelOrder("shop-B", "user-1", "order-1", "reason"));
    verify(orderRepository, never()).findById(anyString());
  }

  @Test
  void bindPurchaseForAnotherShopDoesNotResolveTheOrder() {
    when(orderRepository.findByIdAndShopId("order-1", "shop-B")).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () -> store.bindPurchase("shop-B", "order-1", "purchase-1"));
  }

  @Test
  void listOpenOrdersIsScopedToTheCallersShop() {
    when(orderRepository.findByShopIdAndStatusOrderByOrderNoDesc("shop-B", CafeOrderStatus.OPEN))
        .thenReturn(List.of());

    assertTrue(store.listOpenOrders("shop-B").isEmpty());
    verify(orderRepository).findByShopIdAndStatusOrderByOrderNoDesc("shop-B", CafeOrderStatus.OPEN);
    verify(orderRepository, never()).findAll();
  }

  @Test
  void aReplayedPunchIsLookedUpWithinTheCallersShop() {
    order.setStatus(CafeOrderStatus.OPEN);
    when(punchRepository.findByShopIdAndIdempotencyKey("shop-B", "key-1"))
        .thenReturn(Optional.empty());
    when(orderRepository.findByIdAndShopId("order-1", "shop-B")).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> store.punch(punch("shop-B")));

    // One shop's idempotency key must never surface another shop's tickets.
    verify(punchRepository).findByShopIdAndIdempotencyKey("shop-B", "key-1");
    verify(kotRepository, never()).findByShopIdAndPunchId(any(), any());
  }
}
