package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * How {@code CheckoutService.updateCart} stores the cart, against a stand-in {@code purchases}
 * collection rather than a {@code verify()}.
 *
 * <p>{@code updateCart} used to end in {@code purchaseRepository.save(existingCart)} — a
 * full-document replace over a snapshot merged earlier in the same request, across several
 * inventory and stock round-trips. {@link CheckoutService} is the shared checkout path for
 * medical, grocery, sports and cafe, so these tests pin two things at once: that a concurrent
 * writer's work is no longer deleted, and that a cart with nobody else writing to it ends up as
 * the exact document the replace produced.
 *
 * <p>Reverting the write in {@code updateCart} to {@code purchaseRepository.save(existingCart)}
 * fails {@link #anAddToCartDoesNotDeleteLinesAFlushAppendedConcurrently} and
 * {@link #aConcurrentCancelAndKotDecrementSurviveAnAddToCart} by name. Reverting the
 * {@code lineRef: {$exists: false}} clause in {@code PurchaseTargetedWriter.identityCondition}
 * fails {@link #editingASellScreenLineDoesNotRequantifyTheFlushedRoundBesideIt} and
 * {@link #removingASellScreenLineDoesNotPullTheFlushedRoundWithIt}; reverting the statement order
 * to {@code $set}, {@code $pull}, {@code $push} fails
 * {@link #theLinesAreWrittenBeforeTheTotalThatCountsThem}.
 */
class CheckoutServiceCartWriteTest {

  private static final String SHOP_ID = "shop-1";
  private static final String BILL_ID = "bill-7";

  private InMemoryPurchases purchases;
  private CheckoutService checkoutService;

  @BeforeEach
  void setUp() {
    purchases = new InMemoryPurchases();
    checkoutService = new CheckoutService();
    ShopRepository shopRepository = mock(ShopRepository.class);
    when(shopRepository.findById(anyString())).thenReturn(Optional.<Shop>empty());
    ReflectionTestUtils.setField(checkoutService, "shopRepository", shopRepository);
    ReflectionTestUtils.setField(checkoutService, "purchaseMapper", mock(PurchaseMapper.class));
    ReflectionTestUtils.setField(checkoutService, "purchaseRepository", purchases.repository());
    ReflectionTestUtils.setField(
        checkoutService,
        "purchaseTargetedWriter",
        new PurchaseTargetedWriter(purchases.mongoTemplate()));
  }

  // ------------------------------------------------------------ the lost update

  @Test
  void anAddToCartDoesNotDeleteLinesAFlushAppendedConcurrently() {
    Purchase seeded = openBill();
    seeded.getItems().add(menuLine("a1", "menu:tea", "Tea", 2, "30.00"));
    seeded.setCafeFlushIds(new ArrayList<>(List.of("flush-1")));
    Purchase cart = purchases.seed(seeded);

    // A second tab flushes a round onto bill 7 while the cashier's Coke is being merged: lines
    // pushed, and the flush id pushed with them in the same statement.
    purchases.interleave(
        () ->
            purchases.mutateStored(
                BILL_ID,
                stored -> {
                  stored
                      .getList("items", Document.class)
                      .add(storedLine("b1", "menu:beer", "Beer", 1));
                  stored.getList("cafeFlushIds", String.class).add("flush-2");
                }));

    addToCart(cart, menuLine("c1", "menu:coke", "Coke", 1, "50.00"));

    Purchase after = purchases.read(BILL_ID);
    assertEquals(
        List.of("a1", "b1", "c1"),
        after.getItems().stream().map(PurchaseItem::getLineRef).toList(),
        "the flushed round is on the bill: the kitchen is already cooking it");
    assertEquals(
        List.of("flush-1", "flush-2"),
        after.getCafeFlushIds(),
        "and the bill still records absorbing that flush, so the tab is not stranded");
  }

  @Test
  void aConcurrentCancelAndKotDecrementSurviveAnAddToCart() {
    Purchase seeded = openBill();
    PurchaseItem tea = menuLine("a1", "menu:tea", "Tea", 3, "30.00");
    tea.setKotSentQuantity(3);
    seeded.getItems().add(tea);
    Purchase cart = purchases.seed(seeded);

    // The cancel path, landing on the very line this request is reducing: a cafeKotCancels push
    // and an items.$.kotSentQuantity decrement.
    purchases.interleave(
        () ->
            purchases.mutateStored(
                BILL_ID,
                stored -> {
                  stored.getList("items", Document.class).get(0).put("kotSentQuantity", 1);
                  stored.put(
                      "cafeKotCancels",
                      new ArrayList<>(
                          List.of(new Document("cancelId", "cancel-1").append("quantity", 2))));
                }));

    // The cashier takes two teas off the bill.
    addToCart(cart, menuLine("a1", "menu:tea", "Tea", -2, "30.00"));

    Purchase after = purchases.read(BILL_ID);
    assertEquals(1, after.getItems().size());
    assertEquals(
        1, after.getItems().get(0).getBaseQuantity(), "the cashier's reduction is stored");
    assertEquals(
        1,
        after.getItems().get(0).getKotSentQuantity(),
        "and the kitchen's decrement is not undone by it");
    assertNotNull(after.getCafeKotCancels(), "the cancellation owed to the kitchen is still there");
    assertEquals(1, after.getCafeKotCancels().size());
  }

  @Test
  void theCartWriteNeverReplacesTheItemsArray() {
    Purchase cart = purchases.seed(billWithTea());

    addToCart(cart, menuLine("c1", "menu:coke", "Coke", 1, "50.00"));

    assertFalse(purchases.fullReplaceUsed(), "a replace is what deletes a concurrent append");
    for (Document statement : purchases.statements()) {
      Document set = (Document) statement.get("$set");
      if (set != null) {
        assertFalse(set.containsKey("items"), "items is written line by line, never as an array");
      }
    }
  }

  @Test
  void editingASellScreenLineDoesNotRequantifyTheFlushedRoundBesideIt() {
    // The state a cafe bill reaches on its own: a Sell-screen Tea, which carries no lineRef
    // because only a flush sets one, sitting beside a Tea the kitchen is already cooking, which a
    // flush $pushed as raw BSON so nothing ever merged the two. Their pairing keys differ
    // ("ref:menu:tea" and "line:L2"), so there is no duplicate and no fallback — and an array
    // filter of {sellableRef: "menu:tea"} alone matches both of them.
    Purchase bill = openBill();
    bill.getItems().add(menuLine(null, "menu:tea", "Tea", 1, "30.00"));
    PurchaseItem flushed = menuLine("L2", "menu:tea", "Tea", 2, "30.00");
    flushed.setKotSentQuantity(2);
    bill.getItems().add(flushed);
    Purchase cart = purchases.seed(bill);
    Document before = purchases.stored(BILL_ID);

    // The cashier takes their own Tea from one to five. Nothing about the flushed round changed.
    Purchase result =
        checkoutService.updateCart(
            cart,
            before,
            List.of(menuLine(null, "menu:tea", "Tea", 4, "30.00")),
            null,
            null,
            null,
            BillingMode.REGULAR);

    List<PurchaseItem> stored = purchases.read(BILL_ID).getItems();
    assertEquals(2, stored.size());
    assertEquals(5, stored.get(0).getBaseQuantity(), "the cashier's own line takes the edit");
    assertEquals(
        2,
        stored.get(1).getBaseQuantity(),
        "and the round already in the kitchen keeps the quantity the ticket was written for");
    assertEquals(
        2, stored.get(1).getKotSentQuantity(), "so the bill still agrees with what was sent");
    assertEquals(
        new BigDecimal("60.00"),
        stored.get(1).getTotalAmount(),
        "and the flushed line's money is untouched, so the bill matches its own grandTotal");
    assertFalse(purchases.fullReplaceUsed());
    assertEquals(purchases.write(result), purchases.stored(BILL_ID));
  }

  @Test
  void removingASellScreenLineDoesNotPullTheFlushedRoundWithIt() {
    // The same mismatch on the $pull side: the condition a removed line is matched by must not
    // also describe the flushed line sharing its sellableRef.
    Purchase bill = openBill();
    bill.getItems().add(menuLine(null, "menu:tea", "Tea", 1, "30.00"));
    PurchaseItem flushed = menuLine("L2", "menu:tea", "Tea", 2, "30.00");
    flushed.setKotSentQuantity(2);
    bill.getItems().add(flushed);
    Purchase cart = purchases.seed(bill);
    Document before = purchases.stored(BILL_ID);

    Purchase result =
        checkoutService.updateCart(
            cart,
            before,
            List.of(menuLine(null, "menu:tea", "Tea", -1, "30.00")),
            null,
            null,
            null,
            BillingMode.REGULAR);

    List<PurchaseItem> stored = purchases.read(BILL_ID).getItems();
    assertEquals(1, stored.size(), "only the cashier's own line leaves the bill");
    assertEquals("L2", stored.get(0).getLineRef(), "the round in the kitchen is still billed");
    assertEquals(2, stored.get(0).getBaseQuantity());
    assertFalse(purchases.fullReplaceUsed());
    assertEquals(purchases.write(result), purchases.stored(BILL_ID));
  }

  // ------------------------------------------- the order the statements are issued in

  @Test
  void theLinesAreWrittenBeforeTheTotalThatCountsThem() {
    // Three statements, no transaction to wrap them in, so the order is the whole guarantee. A
    // failure after the total has been committed but before the line it counts leaves a bill
    // whose stored grandTotal exceeds the sum of its lines — and settlement reads the stored
    // total, so the customer pays for an item that is not on the invoice. Pushing first inverts
    // that: what is left behind is a line not yet counted, which under-invoices visible goods.
    Purchase bill = billWithTea();
    bill.getItems().add(menuLine("b1", "menu:beer", "Beer", 1, "120.00"));
    Purchase cart = purchases.seed(bill);
    Document before = purchases.stored(BILL_ID);

    checkoutService.updateCart(
        cart,
        before,
        List.of(
            menuLine("c1", "menu:coke", "Coke", 1, "50.00"),
            menuLine("b1", "menu:beer", "Beer", -1, "120.00")),
        null,
        null,
        null,
        BillingMode.REGULAR);

    List<String> operators = purchases.statements().stream().map(CheckoutServiceCartWriteTest::operatorOf).toList();
    assertEquals(
        List.of("$push", "$set", "$pull"),
        operators,
        "lines in, then the total, then lines out");
    Document totals = (Document) purchases.statements().get(1).get("$set");
    assertTrue(
        totals.containsKey("grandTotal"),
        "the middle statement is the one carrying the money, so it must follow the $push");
  }

  // --------------------------------------------- unchanged for an uncontended cart

  @Test
  void addingALineStoresExactlyWhatTheFullReplaceStored() {
    assertStoredDocumentMatchesTheReplace(
        billWithTea(), menuLine("c1", "menu:coke", "Coke", 1, "50.00"));
  }

  @Test
  void reducingALineStoresExactlyWhatTheFullReplaceStored() {
    assertStoredDocumentMatchesTheReplace(
        billWithTea(), menuLine("a1", "menu:tea", "Tea", -1, "30.00"));
  }

  @Test
  void removingALineStoresExactlyWhatTheFullReplaceStored() {
    Purchase bill = billWithTea();
    bill.getItems().add(menuLine("b1", "menu:beer", "Beer", 1, "120.00"));
    assertStoredDocumentMatchesTheReplace(bill, menuLine("b1", "menu:beer", "Beer", -1, "120.00"));
  }

  @Test
  void clearingTheCustomerStoresExactlyWhatTheFullReplaceStored() {
    Purchase bill = billWithTea();
    bill.setCustomerId("cust-1");
    bill.setCustomerName("Asha");
    Purchase cart = purchases.seed(bill);
    Document before = purchases.stored(BILL_ID);

    // customerName null: the replace dropped the key, and so must the targeted write.
    Purchase result =
        checkoutService.updateCart(
            cart,
            before,
            List.of(menuLine("c1", "menu:coke", "Coke", 1, "50.00")),
            null,
            null,
            null,
            BillingMode.REGULAR);

    assertFalse(
        purchases.stored(BILL_ID).containsKey("customerName"),
        "a field the request nulled is unset, exactly as the replace omitted it");
    assertEquals(purchases.write(result), purchases.stored(BILL_ID));
  }

  // -------------------------------------------------- the verticals that share this

  @Test
  void aLineWithNoneOfTheCafeFieldsIsStoredIdentically() {
    // A line carrying none of the cafe fields, as every grocery, medical and sports line is: no
    // lineRef, no kotSentQuantity, and no cafeFlushIds on the bill. It is addressed by its
    // sellableRef instead, and the stored document must be what it always was.
    Purchase bill = openBill();
    PurchaseItem soap = menuLine(null, "menu:soap", "Soap", 2, "45.00");
    bill.getItems().add(soap);
    Purchase cart = purchases.seed(bill);
    Document before = purchases.stored(BILL_ID);

    Purchase result =
        checkoutService.updateCart(
            cart,
            before,
            List.of(menuLine(null, "menu:soap", "Soap", 1, "45.00")),
            "retail",
            null,
            null,
            BillingMode.REGULAR);

    assertEquals(3, result.getItems().get(0).getBaseQuantity());
    assertEquals(purchases.write(result), purchases.stored(BILL_ID));
    assertFalse(purchases.fullReplaceUsed());
  }

  @Test
  void twoLinesThatCannotBeToldApartFallBackToTheFullReplaceRatherThanAimAtNothing() {
    // Two lines with the same sellableRef and no lineRef between them. Nothing addresses one and
    // not the other, so a targeted write would hit whichever it found first.
    Purchase bill = openBill();
    bill.getItems().add(menuLine(null, "menu:tea", "Tea", 2, "30.00"));
    bill.getItems().add(menuLine(null, "menu:tea", "Tea, extra hot", 1, "30.00"));
    Purchase cart = purchases.seed(bill);
    Document before = purchases.stored(BILL_ID);

    Purchase result =
        checkoutService.updateCart(
            cart,
            before,
            List.of(menuLine("c1", "menu:coke", "Coke", 1, "50.00")),
            null,
            null,
            null,
            BillingMode.REGULAR);

    assertTrue(
        purchases.fullReplaceUsed(),
        "a line with no identity cannot be aimed at, so the old write is used rather than a wrong one");
    assertEquals(purchases.write(result), purchases.stored(BILL_ID));
  }

  // ---------------------------------------------------------------- settlement

  @Test
  void settlingABillDoesNotDeleteTheKitchenWorkDoneOnItsLinesMeanwhile() {
    Purchase seeded = openBill();
    PurchaseItem tea = menuLine("a1", "menu:tea", "Tea", 2, "30.00");
    tea.setKotSentQuantity(2);
    seeded.getItems().add(tea);
    seeded.setCafeFlushIds(new ArrayList<>(List.of("flush-1")));
    Purchase purchase = purchases.seed(seeded);

    PurchaseTargetedWriter writer = new PurchaseTargetedWriter(purchases.mongoTemplate());
    Document before = writer.snapshot(purchase);

    // The cancel path landing mid-settlement: it touches the lines but absorbs no new flush, so
    // there is no total this settlement did not see and the settlement is right to proceed.
    purchases.interleave(
        () ->
            purchases.mutateStored(
                BILL_ID,
                stored -> {
                  stored.getList("items", Document.class).get(0).put("kotSentQuantity", 1);
                  stored.put(
                      "cafeKotCancels",
                      new ArrayList<>(
                          List.of(new Document("cancelId", "cancel-1").append("quantity", 1))));
                }));

    // What updatePurchaseStatus does on completion: the status, the invoice number, the sale date.
    purchase.setStatus(PurchaseStatus.COMPLETED);
    purchase.setInvoiceNo("INV-0001");
    purchase.setPaymentMethod("CASH");
    purchase.setSoldAt(Instant.now());
    purchase.setUpdatedAt(Instant.now());
    assertEquals(1L, writer.writeChangedFields(SHOP_ID, purchase, before));

    Purchase after = purchases.read(BILL_ID);
    assertEquals(PurchaseStatus.COMPLETED, after.getStatus());
    assertEquals("INV-0001", after.getInvoiceNo(), "the invoice number is issued once and stored");
    assertEquals(
        1,
        after.getItems().get(0).getKotSentQuantity(),
        "the kitchen's decrement is not undone by the settlement");
    assertNotNull(after.getCafeKotCancels(), "nor is the cancellation owed to the kitchen");
  }

  @Test
  void aFlushLandingUnderASettlementMakesItFailLoudlyInsteadOfStampingAStaleTotal() {
    // storeTotals recomputes the money fields when a flush lands on a CREATED bill, but this
    // settlement's payment split, ledger, credit entry and printed response were all derived from
    // the total it read before that. Its own update does not carry grandTotal — it did not change
    // it — so without a guard it would happily stamp COMPLETED onto a bill whose stored total no
    // longer equals cash + online + credit. Guarded on the cafeFlushIds it read, it matches
    // nothing instead, and the caller logs that.
    Purchase seeded = openBill();
    seeded.getItems().add(menuLine("a1", "menu:tea", "Tea", 2, "30.00"));
    seeded.setCafeFlushIds(new ArrayList<>(List.of("flush-1")));
    Purchase purchase = purchases.seed(seeded);

    PurchaseTargetedWriter writer = new PurchaseTargetedWriter(purchases.mongoTemplate());
    Document before = writer.snapshot(purchase);

    purchases.interleave(
        () ->
            purchases.mutateStored(
                BILL_ID,
                stored -> {
                  stored
                      .getList("items", Document.class)
                      .add(storedLine("b1", "menu:beer", "Beer", 1));
                  stored.getList("cafeFlushIds", String.class).add("flush-2");
                }));

    purchase.setStatus(PurchaseStatus.COMPLETED);
    purchase.setInvoiceNo("INV-0001");
    purchase.setPaymentMethod("CASH");
    purchase.setSoldAt(Instant.now());
    purchase.setUpdatedAt(Instant.now());

    assertEquals(
        0L,
        writer.writeChangedFields(SHOP_ID, purchase, before),
        "the settlement matched nothing, which updatePurchaseStatus logs");

    Purchase after = purchases.read(BILL_ID);
    assertEquals(
        PurchaseStatus.CREATED, after.getStatus(), "the bill is not settled on a total it never saw");
    assertEquals(
        List.of("a1", "b1"),
        after.getItems().stream().map(PurchaseItem::getLineRef).toList(),
        "and the flushed round is still on the bill, not erased by a settlement that failed");
    assertEquals(List.of("flush-1", "flush-2"), after.getCafeFlushIds());
  }

  @Test
  void anUncontendedSettlementStoresExactlyWhatTheFullReplaceStored() {
    Purchase purchase = purchases.seed(billWithTea());
    PurchaseTargetedWriter writer = new PurchaseTargetedWriter(purchases.mongoTemplate());
    Document before = writer.snapshot(purchase);

    purchase.setStatus(PurchaseStatus.COMPLETED);
    purchase.setInvoiceNo("INV-0001");
    purchase.setTxnId("txn-1");
    purchase.setPaymentMethod("UPI");
    purchase.setSoldAt(Instant.now());
    purchase.setUpdatedAt(Instant.now());
    writer.writeChangedFields(SHOP_ID, purchase, before);

    assertEquals(purchases.write(purchase), purchases.stored(BILL_ID));
  }

  // ------------------------------------------------------------------- helpers

  private void addToCart(Purchase cart, PurchaseItem line) {
    checkoutService.updateCart(
        cart, purchases.stored(BILL_ID), List.of(line), null, null, null, BillingMode.REGULAR);
  }

  /**
   * The whole point of the change, stated once: with nobody else writing, the stored document is
   * the document the full-document replace would have stored, field for field.
   */
  private void assertStoredDocumentMatchesTheReplace(Purchase bill, PurchaseItem line) {
    Purchase cart = purchases.seed(bill);
    Document before = purchases.stored(BILL_ID);

    Purchase result =
        checkoutService.updateCart(
            cart, before, List.of(line), null, null, null, BillingMode.REGULAR);

    assertEquals(purchases.write(result), purchases.stored(BILL_ID));
    assertFalse(purchases.fullReplaceUsed());
  }

  /** The single update operator a statement uses; these are never mixed in one statement. */
  private static String operatorOf(Document statement) {
    for (java.util.Map.Entry<String, Object> entry : statement.entrySet()) {
      if (entry.getValue() instanceof Document body && !body.isEmpty()) {
        return entry.getKey();
      }
    }
    return "";
  }

  private static Purchase billWithTea() {
    Purchase bill = openBill();
    bill.getItems().add(menuLine("a1", "menu:tea", "Tea", 2, "30.00"));
    return bill;
  }

  private static Purchase openBill() {
    Purchase bill = new Purchase();
    bill.setId(BILL_ID);
    bill.setShopId(SHOP_ID);
    bill.setUserId("user-1");
    bill.setStatus(PurchaseStatus.CREATED);
    bill.setBillingMode(BillingMode.REGULAR);
    bill.setItems(new ArrayList<>());
    bill.setCreatedAt(Instant.now());
    bill.setUpdatedAt(Instant.now());
    return bill;
  }

  private static PurchaseItem menuLine(
      String lineRef, String sellableRef, String name, int baseQuantity, String price) {
    PurchaseItem item = new PurchaseItem();
    item.setLineRef(lineRef);
    item.setSellableRef(sellableRef);
    item.setSellMode("menu");
    item.setName(name);
    item.setBaseQuantity(baseQuantity);
    item.setQuantity(BigDecimal.valueOf(baseQuantity));
    item.setSaleUnit("UNIT");
    item.setPriceToRetail(new BigDecimal(price));
    item.setMaximumRetailPrice(new BigDecimal(price));
    item.setTotalAmount(new BigDecimal(price).multiply(BigDecimal.valueOf(baseQuantity)));
    return item;
  }

  /** The same line as the server holds it, for a concurrent writer reaching the document. */
  private static Document storedLine(
      String lineRef, String sellableRef, String name, int baseQuantity) {
    return new Document("lineRef", lineRef)
        .append("sellableRef", sellableRef)
        .append("sellMode", "menu")
        .append("name", name)
        .append("baseQuantity", baseQuantity)
        .append("saleUnit", "UNIT");
  }
}
