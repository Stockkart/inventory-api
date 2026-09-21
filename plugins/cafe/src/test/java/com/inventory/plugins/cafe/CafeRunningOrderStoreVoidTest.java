package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.integration.ShopMenuLookup;
import com.inventory.pluginengine.order.VoidResult;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotLine;
import com.inventory.plugins.cafe.domain.CafeKotStatus;
import com.inventory.plugins.cafe.domain.CafeLineStatus;
import com.inventory.plugins.cafe.domain.CafeOrder;
import com.inventory.plugins.cafe.domain.CafeOrderLine;
import com.inventory.plugins.cafe.domain.CafeOrderStatus;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import com.inventory.plugins.cafe.domain.CafeOrderPunchRepository;
import com.inventory.plugins.cafe.domain.CafeOrderRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CafeRunningOrderStoreVoidTest {

  private CafeOrderRepository orderRepository;
  private CafeKotRepository kotRepository;
  private CafeOrderPunchRepository punchRepository;
  private CafeRunningOrderStore store;
  private CafeOrder order;
  private CafeKot kot;

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
    order.setStatus(CafeOrderStatus.OPEN);
    order.setLines(new ArrayList<>(List.of(line("l1"), line("l2"))));

    kot = new CafeKot();
    kot.setId("kot-1");
    kot.setShopId("shop-1");
    kot.setOrderId("order-1");
    kot.setStatus(CafeKotStatus.ISSUED);
    kot.setReprintCount(0);
    kot.setLines(List.of(kotLine("l1"), kotLine("l2")));

    when(orderRepository.findByIdAndShopId("order-1", "shop-1")).thenReturn(Optional.of(order));
    when(orderRepository.save(any(CafeOrder.class))).thenAnswer(i -> i.getArgument(0));
    when(kotRepository.findByIdAndShopId("kot-1", "shop-1")).thenReturn(Optional.of(kot));
    when(kotRepository.save(any(CafeKot.class))).thenAnswer(i -> i.getArgument(0));
    when(kotRepository.findByShopIdAndOrderId("shop-1", "order-1")).thenReturn(List.of(kot));
  }

  private CafeOrderLine line(String id) {
    CafeOrderLine l = new CafeOrderLine();
    l.setLineId(id);
    l.setKotId("kot-1");
    l.setName(id);
    l.setQuantity(1);
    l.setStatus(CafeLineStatus.ACTIVE);
    return l;
  }

  private CafeKotLine kotLine(String id) {
    CafeKotLine l = new CafeKotLine();
    l.setLineId(id);
    l.setName(id);
    l.setQuantity(1);
    return l;
  }

  @Test
  void voidingSomeLinesLeavesTicketIssued() {
    VoidResult result = store.voidLines("shop-1", "user-1", "kot-1", List.of("l1"), "wrong dish");

    assertEquals("ISSUED", result.getKot().getStatus());
    assertEquals(CafeLineStatus.VOIDED, order.getLines().get(0).getStatus());
    assertEquals(CafeLineStatus.ACTIVE, order.getLines().get(1).getStatus());
  }

  @Test
  void voidingEveryLineVoidsTheTicket() {
    VoidResult result = store.voidLines("shop-1", "user-1", "kot-1", List.of("l1", "l2"), "table left");

    assertEquals("VOIDED", result.getKot().getStatus());
    assertEquals("table left", result.getReason());
  }

  @Test
  void voidingTheLastRemainingActiveLineVoidsTheTicket() {
    store.voidLines("shop-1", "user-1", "kot-1", List.of("l1"), "first");

    VoidResult result = store.voidLines("shop-1", "user-1", "kot-1", List.of("l2"), "second");

    assertEquals("VOIDED", result.getKot().getStatus());
  }

  @Test
  void emptyLineIdsRejected() {
    assertThrows(
        ValidationException.class,
        () -> store.voidLines("shop-1", "user-1", "kot-1", List.of(), "reason"));
  }

  @Test
  void blankReasonRejected() {
    assertThrows(
        ValidationException.class,
        () -> store.voidLines("shop-1", "user-1", "kot-1", List.of("l1"), "  "));
  }

  @Test
  void lineFromAnotherTicketRejected() {
    assertThrows(
        ValidationException.class,
        () -> store.voidLines("shop-1", "user-1", "kot-1", List.of("l-other"), "reason"));
  }

  @Test
  void alreadyVoidedLineRejected() {
    order.getLines().get(0).setStatus(CafeLineStatus.VOIDED);
    assertThrows(
        ValidationException.class,
        () -> store.voidLines("shop-1", "user-1", "kot-1", List.of("l1"), "reason"));
  }

  @Test
  void voidingAnAlreadyVoidedTicketRejected() {
    kot.setStatus(CafeKotStatus.VOIDED);
    assertThrows(
        ValidationException.class,
        () -> store.voidLines("shop-1", "user-1", "kot-1", List.of("l1"), "reason"));
  }

  @Test
  void aRejectedVoidChangesNothing() {
    assertThrows(
        ValidationException.class,
        () -> store.voidLines("shop-1", "user-1", "kot-1", List.of("l1", "l-other"), "reason"));

    assertEquals(CafeLineStatus.ACTIVE, order.getLines().get(0).getStatus());
    verify(orderRepository, never()).save(any());
    verify(kotRepository, never()).save(any());
  }

  @Test
  void unknownTicketIsNotFound() {
    when(kotRepository.findByIdAndShopId("kot-x", "shop-1")).thenReturn(Optional.empty());
    assertThrows(
        ResourceNotFoundException.class,
        () -> store.voidLines("shop-1", "user-1", "kot-x", List.of("l1"), "reason"));
  }

  @Test
  void aPartialVoidReportsOnlyItsOwnLinesAndDoesNotCloseTheTicket() {
    VoidResult result =
        store.voidLines("shop-1", "user-1", "kot-1", List.of("l1"), "dropped on floor");

    assertEquals("ISSUED", result.getKot().getStatus());
    assertEquals(false, result.isTicketFullyVoided());
    assertEquals(List.of("l1"), result.getVoidedLines().stream().map(l -> l.getLineId()).toList());
  }

  @Test
  void twoVoidsProduceTwoDistinctBatchesEachNamingOnlyItsOwnLine() {
    VoidResult first = store.voidLines("shop-1", "user-1", "kot-1", List.of("l1"), "first");
    VoidResult second = store.voidLines("shop-1", "user-1", "kot-1", List.of("l2"), "second");

    // The second slip must not re-list l1; a document derived from current state would.
    assertEquals(List.of("l1"), first.getVoidedLines().stream().map(l -> l.getLineId()).toList());
    assertEquals(List.of("l2"), second.getVoidedLines().stream().map(l -> l.getLineId()).toList());
    org.junit.jupiter.api.Assertions.assertNotEquals(
        first.getVoidBatchId(), second.getVoidBatchId());
    assertEquals(true, second.isTicketFullyVoided());
  }

  @Test
  void aVoidBatchCanBeRefetchedForAReprintOfTheSlip() {
    VoidResult first = store.voidLines("shop-1", "user-1", "kot-1", List.of("l1"), "spilled");

    VoidResult again =
        store.findVoidBatch("shop-1", "kot-1", first.getVoidBatchId()).orElseThrow();

    assertEquals("spilled", again.getReason());
    assertEquals(List.of("l1"), again.getVoidedLines().stream().map(l -> l.getLineId()).toList());
  }

  @Test
  void anUnknownVoidBatchIsEmpty() {
    org.junit.jupiter.api.Assertions.assertTrue(
        store.findVoidBatch("shop-1", "kot-1", "no-such-batch").isEmpty());
  }

  @Test
  void reprintOfVoidedTicketRejected() {
    kot.setStatus(CafeKotStatus.VOIDED);
    assertThrows(ValidationException.class, () -> store.markReprinted("shop-1", "kot-1"));
  }

  @Test
  void reprintIncrementsCount() {
    assertEquals(1, store.markReprinted("shop-1", "kot-1").getReprintCount());
  }

  @Test
  void reprintTolseratesANullCount() {
    kot.setReprintCount(null);
    assertEquals(1, store.markReprinted("shop-1", "kot-1").getReprintCount());
  }

  @Test
  void voidedTicketIsStillReadableForAudit() {
    kot.setStatus(CafeKotStatus.VOIDED);
    assertEquals("VOIDED", store.findKot("shop-1", "kot-1").orElseThrow().getStatus());
  }

  @Test
  void cancelVoidsEveryActiveLineAndTicket() {
    store.cancelOrder("shop-1", "user-1", "order-1", "customer left");

    assertEquals(CafeOrderStatus.CANCELLED, order.getStatus());
    assertEquals(CafeLineStatus.VOIDED, order.getLines().get(0).getStatus());
    assertEquals(CafeLineStatus.VOIDED, order.getLines().get(1).getStatus());
    assertEquals(CafeKotStatus.VOIDED, kot.getStatus());
  }

  @Test
  void cancelSkipsTicketsAlreadyVoided() {
    kot.setStatus(CafeKotStatus.VOIDED);
    kot.setVoidReason("already gone");

    store.cancelOrder("shop-1", "user-1", "order-1", "customer left");

    assertEquals("already gone", kot.getVoidReason());
    verify(kotRepository, never()).save(any(CafeKot.class));
  }

  @Test
  void cancelOfBilledOrderRejected() {
    order.setStatus(CafeOrderStatus.BILLED);
    assertThrows(
        ValidationException.class,
        () -> store.cancelOrder("shop-1", "user-1", "order-1", "reason"));
  }

  @Test
  void cancelWithBlankReasonRejected() {
    assertThrows(
        ValidationException.class, () -> store.cancelOrder("shop-1", "user-1", "order-1", " "));
  }

  @Test
  void bindPurchaseIsIdempotentOnRepeat() {
    store.bindPurchase("shop-1", "order-1", "purchase-9");
    store.bindPurchase("shop-1", "order-1", "purchase-9");
    assertEquals("purchase-9", order.getPurchaseId());
  }

  @Test
  void bindingADifferentPurchaseRejected() {
    store.bindPurchase("shop-1", "order-1", "purchase-9");
    assertThrows(
        ValidationException.class, () -> store.bindPurchase("shop-1", "order-1", "purchase-10"));
  }

  @Test
  void markBilledMovesTheOrderOutOfOpen() {
    assertEquals("BILLED", store.markBilled("shop-1", "user-1", "order-1").getStatus());
  }

  @Test
  void markBilledTwiceRejected() {
    store.markBilled("shop-1", "user-1", "order-1");
    assertThrows(
        ValidationException.class, () -> store.markBilled("shop-1", "user-1", "order-1"));
  }

  @Test
  void anOrderViewReportsHowManyRoundsHaveBeenPunched() {
    when(punchRepository.countByShopIdAndOrderId("shop-1", "order-1")).thenReturn(3L);

    assertEquals(3, store.findOrder("shop-1", "order-1").orElseThrow().getRoundsPunched());
  }
}
