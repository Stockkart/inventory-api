package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.domain.KotStamp;
import com.inventory.documentservice.rest.dto.GenerateKotRequest;
import com.inventory.documentservice.service.KotPdfService;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.order.KotLineView;
import com.inventory.pluginengine.order.KotView;
import com.inventory.pluginengine.order.RunningOrderLineView;
import com.inventory.pluginengine.order.RunningOrderStore;
import com.inventory.pluginengine.order.VoidResult;
import com.inventory.pluginengine.order.RunningOrderView;
import com.inventory.product.rest.dto.request.AddToCartRequest;
import com.inventory.product.rest.dto.request.UpdatePurchaseStatusRequest;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.rest.dto.response.AddToCartResponse;
import com.inventory.product.service.CheckoutService;
import com.inventory.user.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CafeOrderServiceTest {

  private RunningOrderStore store;
  private CheckoutService checkoutService;
  private KotPdfService kotPdfService;
  private RbacService rbacService;
  private CafeOrderService service;
  private HttpServletRequest httpRequest;

  @BeforeEach
  void setUp() {
    store = mock(RunningOrderStore.class);
    checkoutService = mock(CheckoutService.class);
    kotPdfService = mock(KotPdfService.class);
    rbacService = mock(RbacService.class);
    httpRequest = mock(HttpServletRequest.class);

    PluginRegistry registry = mock(PluginRegistry.class);
    VerticalPlugin plugin = mock(VerticalPlugin.class);
    when(plugin.getRunningOrderStore()).thenReturn(Optional.of(store));
    when(registry.require("cafe")).thenReturn(plugin);

    service = new CafeOrderService(registry, checkoutService, kotPdfService, rbacService);
  }

  private RunningOrderView order(String status, String purchaseId) {
    return RunningOrderView.builder()
        .orderId("order-1")
        .shopId("shop-1")
        .orderNo(7)
        .orderType("DINE_IN")
        .tableLabel("T4")
        .status(status)
        .purchaseId(purchaseId)
        .lines(
            List.of(
                RunningOrderLineView.builder()
                    .lineId("l1")
                    .sellableRef("menu:m1")
                    .quantity(2)
                    .status("ACTIVE")
                    .build(),
                RunningOrderLineView.builder()
                    .lineId("l2")
                    .sellableRef("menu:m2")
                    .quantity(1)
                    .status("VOIDED")
                    .build()))
        .build();
  }

  private void cartIsCreatedAs(String purchaseId) {
    AddToCartResponse cart = new AddToCartResponse();
    cart.setPurchaseId(purchaseId);
    when(checkoutService.addToCart(any(AddToCartRequest.class), any())).thenReturn(cart);
    cartHasStatus(purchaseId, PurchaseStatus.CREATED);
  }

  private void cartHasStatus(String purchaseId, PurchaseStatus status) {
    AddToCartResponse view = new AddToCartResponse();
    view.setPurchaseId(purchaseId);
    view.setStatus(status);
    when(checkoutService.getCart(any(), org.mockito.ArgumentMatchers.eq(purchaseId)))
        .thenReturn(view);
  }

  private List<PurchaseStatus> statusesRequested() {
    ArgumentCaptor<UpdatePurchaseStatusRequest> captor =
        ArgumentCaptor.forClass(UpdatePurchaseStatusRequest.class);
    verify(checkoutService, org.mockito.Mockito.atLeastOnce())
        .updatePurchaseStatus(captor.capture(), any());
    return captor.getAllValues().stream().map(UpdatePurchaseStatusRequest::getStatus).toList();
  }

  @Test
  void settleWalksTheRealStatusMachineCreatedThenPendingThenCompleted() {
    when(store.findOrder("shop-1", "order-1")).thenReturn(Optional.of(order("OPEN", null)));
    cartIsCreatedAs("purchase-1");
    when(store.bindPurchase("shop-1", "order-1", "purchase-1"))
        .thenReturn(order("OPEN", "purchase-1"));
    when(store.markBilled("shop-1", "user-1", "order-1"))
        .thenReturn(order("BILLED", "purchase-1"));

    service.settle("shop-1", "user-1", "order-1", "cafe", "CASH", httpRequest);

    // CheckoutValidator permits only CREATED -> PENDING -> COMPLETED; skipping PENDING is rejected.
    assertEquals(
        List.of(PurchaseStatus.PENDING, PurchaseStatus.COMPLETED), statusesRequested());
  }

  @Test
  void settleResumingAPendingCartDoesNotRepeatThePendingStep() {
    when(store.findOrder("shop-1", "order-1"))
        .thenReturn(Optional.of(order("OPEN", "purchase-1")));
    cartHasStatus("purchase-1", PurchaseStatus.PENDING);
    when(store.markBilled("shop-1", "user-1", "order-1"))
        .thenReturn(order("BILLED", "purchase-1"));

    service.settle("shop-1", "user-1", "order-1", "cafe", "CASH", httpRequest);

    assertEquals(List.of(PurchaseStatus.COMPLETED), statusesRequested());
  }

  @Test
  void settleFindingAnAlreadyCompletedPurchaseOnlyMarksTheOrderBilled() {
    when(store.findOrder("shop-1", "order-1"))
        .thenReturn(Optional.of(order("OPEN", "purchase-1")));
    cartHasStatus("purchase-1", PurchaseStatus.COMPLETED);
    when(store.markBilled("shop-1", "user-1", "order-1"))
        .thenReturn(order("BILLED", "purchase-1"));

    service.settle("shop-1", "user-1", "order-1", "cafe", "CASH", httpRequest);

    verify(checkoutService, never()).updatePurchaseStatus(any(), any());
    verify(store).markBilled("shop-1", "user-1", "order-1");
  }

  @Test
  void settleSendsOnlyActiveLinesToCheckout() {
    when(store.findOrder("shop-1", "order-1")).thenReturn(Optional.of(order("OPEN", null)));
    cartIsCreatedAs("purchase-1");
    when(store.bindPurchase("shop-1", "order-1", "purchase-1"))
        .thenReturn(order("OPEN", "purchase-1"));
    when(store.markBilled("shop-1", "user-1", "order-1"))
        .thenReturn(order("BILLED", "purchase-1"));

    service.settle("shop-1", "user-1", "order-1", "RESTAURANT", "CASH", httpRequest);

    ArgumentCaptor<AddToCartRequest> captor = ArgumentCaptor.forClass(AddToCartRequest.class);
    verify(checkoutService).addToCart(captor.capture(), any());
    assertEquals(1, captor.getValue().getItems().size());
    assertEquals("menu:m1", captor.getValue().getItems().get(0).getSellableRef());
    assertEquals("RESTAURANT", captor.getValue().getBusinessType());
  }

  @Test
  void settleRecordsThePurchaseIdBeforeCompletingIt() {
    when(store.findOrder("shop-1", "order-1")).thenReturn(Optional.of(order("OPEN", null)));
    cartIsCreatedAs("purchase-1");
    when(store.bindPurchase("shop-1", "order-1", "purchase-1"))
        .thenReturn(order("OPEN", "purchase-1"));
    when(store.markBilled("shop-1", "user-1", "order-1"))
        .thenReturn(order("BILLED", "purchase-1"));

    service.settle("shop-1", "user-1", "order-1", "RESTAURANT", "CASH", httpRequest);

    // Ordering is the whole safety mechanism: without transactions, a crash after completion but
    // before binding would orphan a paid Purchase.
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(store, checkoutService);
    inOrder.verify(store).bindPurchase("shop-1", "order-1", "purchase-1");
    inOrder.verify(checkoutService, org.mockito.Mockito.atLeastOnce())
        .updatePurchaseStatus(any(), any());
  }

  @Test
  void settleOnBilledOrderReturnsExistingPurchaseAndCreatesNoCart() {
    when(store.findOrder("shop-1", "order-1"))
        .thenReturn(Optional.of(order("BILLED", "purchase-1")));

    RunningOrderView result =
        service.settle("shop-1", "user-1", "order-1", "RESTAURANT", "CASH", httpRequest);

    assertEquals("purchase-1", result.getPurchaseId());
    verify(checkoutService, never()).addToCart(any(), any());
    verify(checkoutService, never()).updatePurchaseStatus(any(), any());
  }

  @Test
  void settleResumesWhenCartAlreadyExists() {
    when(store.findOrder("shop-1", "order-1"))
        .thenReturn(Optional.of(order("OPEN", "purchase-1")));
    cartHasStatus("purchase-1", PurchaseStatus.PENDING);
    when(store.markBilled("shop-1", "user-1", "order-1"))
        .thenReturn(order("BILLED", "purchase-1"));

    service.settle("shop-1", "user-1", "order-1", "RESTAURANT", "CASH", httpRequest);

    verify(checkoutService, never()).addToCart(any(), any());
    ArgumentCaptor<UpdatePurchaseStatusRequest> captor =
        ArgumentCaptor.forClass(UpdatePurchaseStatusRequest.class);
    verify(checkoutService).updatePurchaseStatus(captor.capture(), any());
    assertEquals("purchase-1", captor.getValue().getPurchaseId());
  }

  @Test
  void settleOnCancelledOrderRejected() {
    when(store.findOrder("shop-1", "order-1")).thenReturn(Optional.of(order("CANCELLED", null)));

    assertThrows(
        ValidationException.class,
        () -> service.settle("shop-1", "user-1", "order-1", "RESTAURANT", "CASH", httpRequest));
  }

  @Test
  void settleWithNoActiveLinesRejected() {
    RunningOrderView allVoided =
        RunningOrderView.builder()
            .orderId("order-1")
            .shopId("shop-1")
            .status("OPEN")
            .lines(
                List.of(
                    RunningOrderLineView.builder().lineId("l1").status("VOIDED").build()))
            .build();
    when(store.findOrder("shop-1", "order-1")).thenReturn(Optional.of(allVoided));

    assertThrows(
        ValidationException.class,
        () -> service.settle("shop-1", "user-1", "order-1", "RESTAURANT", "CASH", httpRequest));
    verify(checkoutService, never()).addToCart(any(), any());
  }

  private void voidBatchIs(String batchId, boolean full, String... names) {
    List<KotLineView> lines =
        java.util.Arrays.stream(names)
            .map(n -> KotLineView.builder().lineId(n).name(n).quantity(1).build())
            .toList();
    when(store.findVoidBatch("shop-1", "kot-1", batchId))
        .thenReturn(
            Optional.of(
                VoidResult.builder()
                    .kot(KotView.builder().kotId("kot-1").orderId("order-1")
                        .status(full ? "VOIDED" : "ISSUED").lines(List.of()).build())
                    .voidBatchId(batchId)
                    .reason("customer changed mind")
                    .voidedLines(lines)
                    .ticketFullyVoided(full)
                    .build()));
    when(store.findOrder("shop-1", "order-1")).thenReturn(Optional.of(order("OPEN", null)));
  }

  private GenerateKotRequest renderedSlip() {
    ArgumentCaptor<GenerateKotRequest> captor = ArgumentCaptor.forClass(GenerateKotRequest.class);
    verify(kotPdfService).generateKotPdf(captor.capture());
    return captor.getValue();
  }

  @Test
  void aPartialVoidSlipIsStampedPartialAndListsOnlyTheVoidedLines() {
    voidBatchIs("batch-1", false, "Burger");

    service.voidSlip("shop-1", "kot-1", "batch-1");

    GenerateKotRequest slip = renderedSlip();
    assertEquals(KotStamp.PARTIAL_CANCELLATION, slip.getStamp());
    assertEquals(List.of("Burger"), slip.getItems().stream().map(i -> i.getName()).toList());
    assertEquals("customer changed mind", slip.getVoidReason());
  }

  @Test
  void theSlipThatClosesATicketIsStampedCancelledAndNamesEveryLineOnIt() {
    // Batch killed only Fries, but it was the last active line, so the ticket is dead. The slip
    // names the whole ticket rather than assuming the kitchen still holds the earlier slip.
    when(store.findVoidBatch("shop-1", "kot-1", "batch-2"))
        .thenReturn(
            Optional.of(
                VoidResult.builder()
                    .kot(
                        KotView.builder().kotId("kot-1").orderId("order-1").status("VOIDED")
                            .lines(
                                List.of(
                                    KotLineView.builder().lineId("a").name("Burger").quantity(1).build(),
                                    KotLineView.builder().lineId("b").name("Fries").quantity(1).build()))
                            .build())
                    .voidBatchId("batch-2")
                    .reason("table left")
                    .voidedLines(
                        List.of(KotLineView.builder().lineId("b").name("Fries").quantity(1).build()))
                    .ticketFullyVoided(true)
                    .build()));
    when(store.findOrder("shop-1", "order-1")).thenReturn(Optional.of(order("OPEN", null)));

    service.voidSlip("shop-1", "kot-1", "batch-2");

    GenerateKotRequest slip = renderedSlip();
    assertEquals(KotStamp.CANCELLED, slip.getStamp());
    assertEquals(List.of("Burger", "Fries"), slip.getItems().stream().map(i -> i.getName()).toList());
  }

  @Test
  void anUnknownVoidBatchIsNotFoundAndRendersNothing() {
    when(store.findVoidBatch("shop-1", "kot-1", "nope")).thenReturn(Optional.empty());

    assertThrows(
        com.inventory.common.exception.ResourceNotFoundException.class,
        () -> service.voidSlip("shop-1", "kot-1", "nope"));
    verify(kotPdfService, never()).generateKotPdf(any());
  }

  @Test
  void voidRequiresTheKotVoidModule() {
    doThrow(new BaseException(ErrorCode.ACCESS_DENIED, "denied"))
        .when(rbacService)
        .requireModule("user-1", "shop-1", RbacService.MODULE_KOT_VOID);

    assertThrows(
        BaseException.class,
        () -> service.voidLines("shop-1", "user-1", "kot-1", List.of("l1"), "reason"));
    verify(store, never()).voidLines(any(), any(), any(), any(), any());
  }

  @Test
  void cancelRequiresTheKotVoidModule() {
    doThrow(new BaseException(ErrorCode.ACCESS_DENIED, "denied"))
        .when(rbacService)
        .requireModule("user-1", "shop-1", RbacService.MODULE_KOT_VOID);

    assertThrows(
        BaseException.class, () -> service.cancel("shop-1", "user-1", "order-1", "reason"));
    verify(store, never()).cancelOrder(any(), any(), any(), any());
  }

  @Test
  void anIssuedTicketRendersUnstamped() {
    when(store.findKot("shop-1", "kot-1"))
        .thenReturn(Optional.of(KotView.builder().kotId("kot-1").orderId("order-1")
            .status("ISSUED").lines(List.of()).build()));
    when(store.findOrder("shop-1", "order-1")).thenReturn(Optional.of(order("OPEN", null)));

    service.kotDocument("shop-1", "kot-1");

    ArgumentCaptor<GenerateKotRequest> captor = ArgumentCaptor.forClass(GenerateKotRequest.class);
    verify(kotPdfService).generateKotPdf(captor.capture());
    assertEquals(KotStamp.NONE, captor.getValue().getStamp());
  }

  @Test
  void aVoidedTicketRendersAsACancellationSlip() {
    when(store.findKot("shop-1", "kot-1"))
        .thenReturn(Optional.of(KotView.builder().kotId("kot-1").orderId("order-1")
            .status("VOIDED").voidReason("table left").lines(List.of()).build()));
    when(store.findOrder("shop-1", "order-1"))
        .thenReturn(Optional.of(order("CANCELLED", null)));

    service.kotDocument("shop-1", "kot-1");

    ArgumentCaptor<GenerateKotRequest> captor = ArgumentCaptor.forClass(GenerateKotRequest.class);
    verify(kotPdfService).generateKotPdf(captor.capture());
    assertEquals(KotStamp.CANCELLED, captor.getValue().getStamp());
    assertEquals("table left", captor.getValue().getVoidReason());
  }

  @Test
  void reprintIsStampedAndGoesThroughTheStoreSoTheCountRises() {
    when(store.markReprinted("shop-1", "kot-1"))
        .thenReturn(KotView.builder().kotId("kot-1").orderId("order-1")
            .status("ISSUED").reprintCount(1).lines(List.of()).build());
    when(store.findOrder("shop-1", "order-1")).thenReturn(Optional.of(order("OPEN", null)));

    service.reprint("shop-1", "kot-1");

    ArgumentCaptor<GenerateKotRequest> captor = ArgumentCaptor.forClass(GenerateKotRequest.class);
    verify(kotPdfService).generateKotPdf(captor.capture());
    assertEquals(KotStamp.REPRINT, captor.getValue().getStamp());
  }
}
