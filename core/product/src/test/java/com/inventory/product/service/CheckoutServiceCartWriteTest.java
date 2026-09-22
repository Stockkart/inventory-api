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
 * fails {@link #anAddToCartDoesNotDeleteWhatAPunchWroteConcurrently} and
 * {@link #aConcurrentPunchsKotSentQuantitySurvivesAReduction} by name. Reverting the
 * {@code lineRef: {$exists: false}} clause in {@code PurchaseTargetedWriter.identityCondition}
 * fails {@link #editingASellScreenLineDoesNotRequantifyThePunchedRoundBesideIt} and
 * {@link #removingASellScreenLineDoesNotPullThePunchedRoundWithIt}; reverting the statement order
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
  void anAddToCartDoesNotDeleteWhatAPunchWroteConcurrently() {
    Purchase seeded = openBill();
    PurchaseItem tea = menuLine("a1", "menu:tea", "Tea", 2, "30.00");
    seeded.getItems().add(tea);
    Purchase cart = purchases.seed(seeded);

    // Print KOT is pressed on another terminal while the cashier's Coke is being merged: the
    // punch appends to cafeKotPunches and advances the tea line's kotSentQuantity, both as raw
    // BSON, in one findAndModify. A full replace from the cart read above deletes both -- the
    // tea is in the kitchen and the bill says it never was, so the next press sends it again.
    purchases.interleave(
        () ->
            purchases.mutateStored(
                BILL_ID,
                stored -> {
                  stored.getList("items", Document.class).get(0).put("kotSentQuantity", 2);
                  stored.put(
                      "cafeKotPunches",
                      new ArrayList<>(
                          List.of(
                              new Document("punchId", "punch-1")
                                  .append("idempotencyKey", "idem-1")
                                  .append("status", "COMPLETE"))));
                }));

    addToCart(cart, menuLine("c1", "menu:coke", "Coke", 1, "50.00"));

    Purchase after = purchases.read(BILL_ID);
    assertEquals(
        List.of("a1", "c1"),
        after.getItems().stream().map(PurchaseItem::getLineRef).toList(),
        "both lines are on the bill");
    assertEquals(
        2,
        after.getItems().get(0).getKotSentQuantity(),
        "the tea the kitchen is cooking still records having been sent");
    assertNotNull(after.getCafeKotPunches(), "and the punch that sent it is still on the bill");
    assertEquals(1, after.getCafeKotPunches().size());
  }

  @Test
  void aConcurrentPunchsKotSentQuantitySurvivesAReduction() {
    // The single cancellation path, end to end on the core side: the kitchen has three teas, the
    // cashier takes two off, and what must survive the cart write is kotSentQuantity == 3 beside
    // baseQuantity == 1. That pair IS the cancellation -- the next punch computes 1 - 3 = -2 and
    // issues the CANCEL slip. A cart write that pushed its own kotSentQuantity back over it
    // would erase the only record the cancellation can be derived from.
    Purchase seeded = openBill();
    PurchaseItem tea = menuLine("a1", "menu:tea", "Tea", 3, "30.00");
    seeded.getItems().add(tea);
    Purchase cart = purchases.seed(seeded);

    purchases.interleave(
        () ->
            purchases.mutateStored(
                BILL_ID,
                stored -> stored.getList("items", Document.class).get(0).put("kotSentQuantity", 3)));

    // The cashier takes two teas off the bill.
    addToCart(cart, menuLine("a1", "menu:tea", "Tea", -2, "30.00"));

    Purchase after = purchases.read(BILL_ID);
    assertEquals(1, after.getItems().size());
    assertEquals(
        1, after.getItems().get(0).getBaseQuantity(), "the cashier's reduction is stored");
    assertEquals(
        3,
        after.getItems().get(0).getKotSentQuantity(),
        "and what the kitchen was sent is untouched, so the next punch owes it a CANCEL of 2");
  }

  @Test
  void aLineReducedToNothingStaysOnTheCartAtZeroSoThePunchCanCancelIt() {
    // Removing the line outright would delete the kotSentQuantity the cancellation is computed
    // from: the kitchen would keep cooking food nothing on the bill remembers ordering. The
    // punch sweeps the zeroed line away itself, once it has recorded the negative delta.
    Purchase seeded = openBill();
    PurchaseItem tea = menuLine("a1", "menu:tea", "Tea", 2, "30.00");
    tea.setKotSentQuantity(2);
    seeded.getItems().add(tea);
    Purchase cart = purchases.seed(seeded);

    addToCart(cart, menuLine("a1", "menu:tea", "Tea", -2, "30.00"));

    Purchase after = purchases.read(BILL_ID);
    assertEquals(1, after.getItems().size(), "the line is kept, not pulled");
    assertEquals(0, after.getItems().get(0).getBaseQuantity());
    assertEquals(
        2,
        after.getItems().get(0).getKotSentQuantity(),
        "so 0 - 2 is the CANCEL the next press of Print KOT sends");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(after.getItems().get(0).getTotalAmount()),
        "and the zeroed line is charged nothing");
  }

  @Test
  void anUnsentLineReducedToNothingIsRemovedOutright() {
    // Nothing was ever sent, so nothing is owed and the line has no reason to linger at zero.
    Purchase seeded = openBill();
    seeded.getItems().add(menuLine("a1", "menu:tea", "Tea", 2, "30.00"));
    Purchase cart = purchases.seed(seeded);

    addToCart(cart, menuLine("a1", "menu:tea", "Tea", -2, "30.00"));

    assertTrue(purchases.read(BILL_ID).getItems().isEmpty());
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
  void editingASellScreenLineDoesNotRequantifyThePunchedRoundBesideIt() {
    // A Sell-screen Tea with no lineRef, sitting beside a Tea the kitchen is already cooking that
    // carries one -- the shape every bill written before the punch subsystem has, and the reason
    // lineRef is still the most specific line identity. Their pairing keys differ
    // ("ref:menu:tea" and "line:L2"), so there is no duplicate and no fallback — and an array
    // filter of {sellableRef: "menu:tea"} alone matches both of them.
    Purchase bill = openBill();
    bill.getItems().add(menuLine(null, "menu:tea", "Tea", 1, "30.00"));
    PurchaseItem sent = menuLine("L2", "menu:tea", "Tea", 2, "30.00");
    sent.setKotSentQuantity(2);
    bill.getItems().add(sent);
    Purchase cart = purchases.seed(bill);
    Document before = purchases.stored(BILL_ID);

    // The cashier takes their own Tea from one to five. Nothing about the sent round changed.
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
        "and the sent line's money is untouched, so the bill matches its own grandTotal");
    assertFalse(purchases.fullReplaceUsed());
    assertEquals(purchases.write(result), purchases.stored(BILL_ID));
  }

  @Test
  void removingASellScreenLineDoesNotPullThePunchedRoundWithIt() {
    // The same mismatch on the $pull side: the condition a removed line is matched by must not
    // also describe the sent line sharing its sellableRef.
    Purchase bill = openBill();
    bill.getItems().add(menuLine(null, "menu:tea", "Tea", 1, "30.00"));
    PurchaseItem sent = menuLine("L2", "menu:tea", "Tea", 2, "30.00");
    sent.setKotSentQuantity(2);
    bill.getItems().add(sent);
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
        List.of("$push", "$set", "$set", "$pull"),
        operators,
        "lines in, then the line edits, then the total, then lines out");
    // Two $set statements rather than one: a line edit is aimed at a named line and is right
    // whatever else landed on the bill, while the money is arithmetic over every line. Keeping
    // them apart is what lets the total be issued after the lines it counts.
    Document lineEdits = (Document) purchases.statements().get(1).get("$set");
    assertFalse(
        lineEdits.containsKey("grandTotal"),
        "the line edits carry no money");
    Document totals = (Document) purchases.statements().get(2).get("$set");
    assertTrue(
        totals.containsKey("grandTotal"),
        "the money statement follows the $push and precedes the $pull");
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
    // lineRef and no kotSentQuantity. It is addressed by its sellableRef instead, and the stored
    // document must be what it always was.
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
    seeded.getItems().add(tea);
    Purchase purchase = purchases.seed(seeded);

    PurchaseTargetedWriter writer = new PurchaseTargetedWriter(purchases.mongoTemplate());
    Document before = writer.snapshot(purchase);

    // A punch landing mid-settlement: it advances the lines and appends its record, but it does
    // not touch the money, so there is no total this settlement did not see and it is right to
    // proceed.
    purchases.interleave(
        () ->
            purchases.mutateStored(
                BILL_ID,
                stored -> {
                  stored.getList("items", Document.class).get(0).put("kotSentQuantity", 2);
                  stored.put(
                      "cafeKotPunches",
                      new ArrayList<>(
                          List.of(new Document("punchId", "punch-1").append("status", "COMPLETE"))));
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
        2,
        after.getItems().get(0).getKotSentQuantity(),
        "the punch's advance is not undone by the settlement");
    assertNotNull(after.getCafeKotPunches(), "nor is the punch record itself");
  }

  @Test
  void aRecomputedTotalUnderASettlementMakesItFailLoudlyInsteadOfStampingAStaleOne() {
    // This settlement's payment split, ledger, credit entry and printed response were all derived
    // from the total it read. Its own update does not carry grandTotal — it did not change it —
    // so without a guard it would happily stamp COMPLETED onto a bill whose stored total no
    // longer equals cash + online + credit. Guarded on the grandTotal it read, it matches nothing
    // instead, and the caller logs that. (The guard used to name cafeFlushIds, the array the
    // retired flush pushed to; it now names the field it was always protecting.)
    Purchase seeded = openBill();
    seeded.getItems().add(menuLine("a1", "menu:tea", "Tea", 2, "30.00"));
    seeded.setGrandTotal(new BigDecimal("60.00"));
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
                  stored.put(
                      "grandTotal", new org.bson.types.Decimal128(new BigDecimal("180.00")));
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
        "and the line added under it is still on the bill, not erased by a settlement that failed");
    assertEquals(new BigDecimal("180.00"), after.getGrandTotal());
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
