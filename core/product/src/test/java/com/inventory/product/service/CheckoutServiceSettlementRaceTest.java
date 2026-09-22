package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.rest.dto.request.RecordUsageRequest;
import com.inventory.plan.service.UsageService;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.mapper.PurchaseMapper;
import com.inventory.product.rest.dto.request.UpdatePurchaseStatusRequest;
import com.inventory.product.rest.dto.response.CheckoutResponse;
import com.inventory.product.service.vertical.CheckoutCompletionOrchestrator;
import com.inventory.product.validation.CheckoutValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * B1: what {@code CheckoutService.updatePurchaseStatus} does when its guarded settlement write
 * matches nothing.
 *
 * <p>The write is guarded on the {@code grandTotal} the settlement read, so anything that
 * recomputes the bill's money between that read and the write makes it match 0. By that point the stock
 * has been decremented, an invoice number has been consumed and a txnId minted — but no money has
 * moved: the billing usage, the ledger, the credit entry and the receipt all come after. A 0 that
 * is only logged leaves every one of those posted against a document still {@code CREATED} with
 * no {@code invoiceNo}, still in the open-bill strip, and settleable a second time.
 *
 * <p>So a 0 aborts the request. Reverting the abort in {@code updatePurchaseStatus} to the
 * {@code log.warn} it used to be fails
 * {@link #aRepricedBillMidSettlementAbortsBeforeAnyMoneyMoves}
 * and {@link #anAbortedSettlementLeavesTheBillOpenAndUnnumbered} by name.
 *
 * <p>{@link #anUncontendedSettlementStillCompletes} is the other half: this is the shared checkout
 * path for medical, grocery, sports and cafe, and a bill nobody else is writing to must settle
 * exactly as it always has.
 */
class CheckoutServiceSettlementRaceTest {

  private static final String SHOP_ID = "shop-1";
  private static final String USER_ID = "user-1";
  private static final String BILL_ID = "bill-7";

  private InMemoryPurchases purchases;
  private CheckoutService checkoutService;
  private UsageService usageService;
  private CheckoutCompletionOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    purchases = new InMemoryPurchases();
    checkoutService = new CheckoutService();
    usageService = mock(UsageService.class);
    orchestrator = mock(CheckoutCompletionOrchestrator.class);
    when(orchestrator.onPurchaseCompleted(any(Purchase.class))).thenReturn(Optional.empty());

    PurchaseMapper purchaseMapper = mock(PurchaseMapper.class);
    when(purchaseMapper.toCheckoutResponse(any(Purchase.class))).thenReturn(new CheckoutResponse());

    InvoiceSequenceService invoiceSequenceService = mock(InvoiceSequenceService.class);
    when(invoiceSequenceService.getNextInvoiceNo(anyString())).thenReturn("INV-0001");

    ReflectionTestUtils.setField(checkoutService, "purchaseRepository", purchases.repository());
    ReflectionTestUtils.setField(checkoutService, "purchaseMapper", purchaseMapper);
    ReflectionTestUtils.setField(checkoutService, "checkoutValidator", new CheckoutValidator());
    ReflectionTestUtils.setField(checkoutService, "usageService", usageService);
    ReflectionTestUtils.setField(
        checkoutService, "invoiceSequenceService", invoiceSequenceService);
    ReflectionTestUtils.setField(checkoutService, "checkoutCompletionOrchestrator", orchestrator);
    ReflectionTestUtils.setField(
        checkoutService,
        "purchaseTargetedWriter",
        new PurchaseTargetedWriter(purchases.mongoTemplate()));
  }

  @Test
  void aRepricedBillMidSettlementAbortsBeforeAnyMoneyMoves() {
    purchases.seed(cafeBill());
    repriceTheBillDuringTheSettlementWrite();

    ValidationException refused =
        assertThrows(ValidationException.class, () -> settle(PurchaseStatus.COMPLETED));

    // The message names what actually happened, so the cashier reloads rather than re-presses.
    org.junit.jupiter.api.Assertions.assertTrue(
        refused.getMessage().contains("changed while it was being settled"),
        "the error names the cause: " + refused.getMessage());

    // Nothing downstream of the write ran. These are the irreversible half of the settlement --
    // posting them against a bill that is still open is the whole of the defect.
    verify(usageService, never()).recordUsage(anyString(), any(RecordUsageRequest.class));
    verify(orchestrator, never()).onPurchaseCompleted(any(Purchase.class));
  }

  @Test
  void anAbortedSettlementLeavesTheBillOpenAndUnnumbered() {
    purchases.seed(cafeBill());
    repriceTheBillDuringTheSettlementWrite();

    assertThrows(ValidationException.class, () -> settle(PurchaseStatus.COMPLETED));

    Purchase stored = purchases.read(BILL_ID);
    assertEquals(
        PurchaseStatus.PENDING,
        stored.getStatus(),
        "the bill is not settled, which is what makes the retry safe");
    assertNull(stored.getInvoiceNo(), "and carries no invoice number from the attempt");
    assertEquals(
        List.of("a1", "b1"),
        stored.getItems().stream().map(PurchaseItem::getLineRef).toList(),
        "the line added under it is still on the bill for the retry to price");
  }

  @Test
  void anUncontendedSettlementStillCompletes() {
    purchases.seed(cafeBill());

    checkoutService.updatePurchaseStatus(request(PurchaseStatus.COMPLETED), httpRequest());

    Purchase stored = purchases.read(BILL_ID);
    assertEquals(PurchaseStatus.COMPLETED, stored.getStatus());
    assertEquals("INV-0001", stored.getInvoiceNo());
    verify(usageService).recordUsage(anyString(), any(RecordUsageRequest.class));
  }

  @Test
  void aBillNobodyRepricedSettlesWhateverItsVertical() {
    // The guard names no vertical: a grocery, medical or sports bill whose total nobody touched
    // matches on equality exactly as a cafe one does.
    Purchase grocery = cafeBill();
    grocery.getItems().get(0).setSellMode("retail");
    grocery.getItems().get(0).setSellableRef(null);
    purchases.seed(grocery);

    checkoutService.updatePurchaseStatus(request(PurchaseStatus.COMPLETED), httpRequest());

    assertEquals(PurchaseStatus.COMPLETED, purchases.read(BILL_ID).getStatus());
  }

  // ------------------------------------------------------------------- helpers

  /** The bill's money recomputed in the window the settlement write is guarded on. */
  private void repriceTheBillDuringTheSettlementWrite() {
    purchases.interleave(
        () ->
            purchases.mutateStored(
                BILL_ID,
                stored -> {
                  stored
                      .getList("items", Document.class)
                      .add(
                          new Document("lineRef", "b1")
                              .append("sellableRef", "menu:beer")
                              .append("sellMode", "menu")
                              .append("name", "Beer")
                              .append("baseQuantity", 1));
                  stored.put(
                      "grandTotal", new org.bson.types.Decimal128(new BigDecimal("180.00")));
                }));
  }

  private void settle(PurchaseStatus status) {
    checkoutService.updatePurchaseStatus(request(status), httpRequest());
  }

  private static UpdatePurchaseStatusRequest request(PurchaseStatus status) {
    UpdatePurchaseStatusRequest request = new UpdatePurchaseStatusRequest();
    request.setPurchaseId(BILL_ID);
    request.setStatus(status);
    request.setPaymentMethod("CASH");
    return request;
  }

  private static HttpServletRequest httpRequest() {
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getAttribute("shopId")).thenReturn(SHOP_ID);
    when(http.getAttribute("userId")).thenReturn(USER_ID);
    return http;
  }

  /** An open cafe bill with one line already on it. */
  private static Purchase cafeBill() {
    Purchase bill = new Purchase();
    bill.setId(BILL_ID);
    bill.setShopId(SHOP_ID);
    bill.setUserId(USER_ID);
    // PENDING: the checkout screen moves an open bill to PENDING and then settles it, and
    // CREATED -> COMPLETED is not a transition the validator allows.
    bill.setStatus(PurchaseStatus.PENDING);
    bill.setBillingMode(BillingMode.REGULAR);
    bill.setGrandTotal(new BigDecimal("60.00"));
    bill.setCreatedAt(Instant.now());
    bill.setUpdatedAt(Instant.now());

    PurchaseItem tea = new PurchaseItem();
    tea.setLineRef("a1");
    tea.setSellableRef("menu:tea");
    tea.setSellMode("menu");
    tea.setName("Tea");
    tea.setBaseQuantity(2);
    tea.setQuantity(BigDecimal.valueOf(2));
    tea.setSaleUnit("UNIT");
    tea.setPriceToRetail(new BigDecimal("30.00"));
    tea.setTotalAmount(new BigDecimal("60.00"));
    bill.setItems(new ArrayList<>(List.of(tea)));
    return bill;
  }
}
