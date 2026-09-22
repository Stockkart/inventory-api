package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.mapper.PurchaseMapper;
import com.inventory.product.service.CheckoutService;
import com.mongodb.client.result.UpdateResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The recompute a cafe flush asks for, against the real arithmetic rather than a {@code verify()}.
 *
 * <p>Two things are asserted here and nowhere else. The first is the number: a known basket's
 * {@code grandTotal}, because checkout settles against the stored total and "a method was called"
 * is not evidence that the shop is paid. The second is that the write is non-destructive — a
 * recompute runs on a snapshot, and the whole point of writing the money fields one by one is
 * that a line another writer appended in the gap is still on the bill afterwards.
 *
 * <p>{@link CheckoutService} is the real one, with only the two collaborators its totals path
 * touches stubbed; the arithmetic under test is its own.
 */
class CartTotalsAdapterTest {

  private static final String SHOP_ID = "shop-1";
  private static final String BILL_ID = "bill-1";

  private final Map<String, Purchase> store = new LinkedHashMap<>();
  private PurchaseRepository purchaseRepository;
  private MongoTemplate mongoTemplate;
  private CartTotalsAdapter adapter;

  /** Runs after the snapshot is read and before the totals are written: the interleaving. */
  private Consumer<Map<String, Purchase>> betweenReadAndWrite;

  @BeforeEach
  void setUp() {
    purchaseRepository = mock(PurchaseRepository.class);
    when(purchaseRepository.findById(anyString()))
        .thenAnswer(
            invocation -> {
              Purchase stored = store.get(invocation.<String>getArgument(0));
              // A snapshot, as a read from the server is: mutating it cannot reach the store.
              return Optional.ofNullable(stored).map(CartTotalsAdapterTest::copy);
            });
    when(purchaseRepository.save(any(Purchase.class)))
        .thenAnswer(
            invocation -> {
              // The full-document replace. Nothing under test should call it; when something does,
              // it writes items as the caller read them and the interleaving test fails by name.
              Purchase saved = invocation.getArgument(0);
              store.put(saved.getId(), saved);
              return saved;
            });

    mongoTemplate = mock(MongoTemplate.class);
    when(mongoTemplate.updateFirst(
            any(Query.class), any(UpdateDefinition.class), eq(Purchase.class)))
        .thenAnswer(
            invocation ->
                applyTargetedSet(invocation.getArgument(0), invocation.getArgument(1)));

    adapter = new CartTotalsAdapter(purchaseRepository, checkoutService(), mongoTemplate);
  }

  /** The real totals arithmetic; only the collaborators it reaches for are stubbed. */
  private static CheckoutService checkoutService() {
    CheckoutService checkoutService = new CheckoutService();
    ShopRepository shopRepository = mock(ShopRepository.class);
    when(shopRepository.findById(anyString())).thenReturn(Optional.empty());
    ReflectionTestUtils.setField(checkoutService, "shopRepository", shopRepository);
    ReflectionTestUtils.setField(checkoutService, "purchaseMapper", mock(PurchaseMapper.class));
    return checkoutService;
  }

  // ------------------------------------------------------------------ the money

  @Test
  void theBillsGrandTotalIsWhatTheBasketComesTo() {
    Purchase bill = openBill();
    bill.getItems().add(menuLine("l1", "Tea", 2, "30.00", "2.5", "2.5"));
    bill.getItems().add(menuLine("l2", "Beer", 1, "120.00", null, null));
    store.put(BILL_ID, bill);

    adapter.recalculateTotals(SHOP_ID, BILL_ID);

    Purchase stored = store.get(BILL_ID);
    // 2 x Tea at 30.00 = 60.00 and 1 x Beer at 120.00 = 120.00: a subtotal of 180.00, and that is
    // also the grand total the bill settles for.
    //
    // NOT 183. The review's worked example adds the tea's 2.5% + 2.5% on top, and the system does
    // not: a menu line carries maximumRetailPrice EQUAL to priceToRetail — both the flush and the
    // Sell screen's contributor write it that way — and CheckoutUtils.isSellingAtMrp therefore
    // reads the menu price as tax-inclusive, so CheckoutService.calculateTax skips the line
    // entirely. The rates ride onto the line but move no money. Whether a cafe's menu price ought
    // to be tax-inclusive, and if so why the GST is not then extracted from it rather than
    // recorded as zero, is a question about core's tax rules that is the same for every path into
    // a menu line; it is not something the cafe flush decides. This test pins what the shop is
    // actually paid so that a change to it has to be deliberate.
    assertEquals(new BigDecimal("180.00"), stored.getSubTotal());
    assertEquals(BigDecimal.ZERO, stored.getTaxTotal(), "a menu price is read as tax-inclusive");
    assertEquals(BigDecimal.ZERO, stored.getCgstAmount());
    assertEquals(BigDecimal.ZERO, stored.getSgstAmount());
    assertEquals(new BigDecimal("180"), stored.getGrandTotal(), "the round is paid for");
  }

  // --------------------------------------------------------- the lost update

  @Test
  void aSecondFlushesLinesSurviveTheFirstsRecompute() {
    Purchase bill = openBill();
    bill.getItems().add(menuLine("a1", "Tea", 2, "30.00", "2.5", "2.5"));
    store.put(BILL_ID, bill);

    // Flush A has appended and read the bill. Flush B — a different tab, the same target bill —
    // appends its own round in the gap, and its tickets are already at the pass.
    betweenReadAndWrite =
        purchases ->
            purchases
                .get(BILL_ID)
                .getItems()
                .add(menuLine("b1", "Beer", 1, "120.00", null, null));

    adapter.recalculateTotals(SHOP_ID, BILL_ID);

    List<String> refs = store.get(BILL_ID).getItems().stream().map(PurchaseItem::getLineRef).toList();
    assertEquals(
        List.of("a1", "b1"),
        refs,
        "B's line is on the bill: the cook is making it and the bill must record it");
  }

  @Test
  void aConcurrentCancelSurvivesTheRecompute() {
    Purchase bill = openBill();
    PurchaseItem tea = menuLine("a1", "Tea", 2, "30.00", "2.5", "2.5");
    tea.setKotSentQuantity(2);
    bill.getItems().add(tea);
    store.put(BILL_ID, bill);

    // The cancel path: a cafeKotCancels push and an items.$.kotSentQuantity decrement, landing
    // while this recompute holds a snapshot that has neither.
    betweenReadAndWrite =
        purchases -> {
          Purchase live = purchases.get(BILL_ID);
          live.getItems().get(0).setKotSentQuantity(1);
          live.setCafeKotCancels(new ArrayList<>(List.of(new com.inventory.product.domain.model.CafeKotCancel())));
        };

    adapter.recalculateTotals(SHOP_ID, BILL_ID);

    Purchase stored = store.get(BILL_ID);
    assertEquals(1, stored.getItems().get(0).getKotSentQuantity(), "the decrement is not undone");
    assertNotNull(stored.getCafeKotCancels(), "and the cancellation is still recorded");
    assertEquals(1, stored.getCafeKotCancels().size());
  }

  @Test
  void aBillSettledInTheGapKeepsTheTotalsItWasSettledFor() {
    Purchase bill = openBill();
    bill.getItems().add(menuLine("a1", "Tea", 2, "30.00", "2.5", "2.5"));
    store.put(BILL_ID, bill);

    betweenReadAndWrite =
        purchases -> {
          Purchase live = purchases.get(BILL_ID);
          live.setStatus(PurchaseStatus.COMPLETED);
          live.setGrandTotal(new BigDecimal("999"));
        };

    adapter.recalculateTotals(SHOP_ID, BILL_ID);

    assertEquals(
        new BigDecimal("999"),
        store.get(BILL_ID).getGrandTotal(),
        "the money was taken for that number; a recompute must not rewrite it");
  }

  @Test
  void theRecomputeNeverWritesTheItems() {
    Purchase bill = openBill();
    bill.getItems().add(menuLine("a1", "Tea", 2, "30.00", "2.5", "2.5"));
    store.put(BILL_ID, bill);

    adapter.recalculateTotals(SHOP_ID, BILL_ID);

    assertFalse(writtenFields.contains("items"), "items belong to whoever appended them");
    assertFalse(writtenFields.isEmpty(), "and the money fields were written");
  }

  // ------------------------------------------------------------------ helpers

  private final List<String> writtenFields = new ArrayList<>();

  private UpdateResult applyTargetedSet(Query query, UpdateDefinition update) {
    if (betweenReadAndWrite != null) {
      Consumer<Map<String, Purchase>> hook = betweenReadAndWrite;
      betweenReadAndWrite = null;
      hook.accept(store);
    }
    Document q = query.getQueryObject();
    Purchase live = store.get(q.getString("_id"));
    if (live == null
        || !Objects.equals(q.getString("shopId"), live.getShopId())
        || !matchesStatusClause(q, live)) {
      return UpdateResult.acknowledged(0, 0L, null);
    }
    Document set = (Document) update.getUpdateObject().get("$set");
    set.keySet().forEach(writtenFields::add);
    set.forEach((field, value) -> apply(live, field, value));
    return UpdateResult.acknowledged(1, 1L, null);
  }

  /** {@code $or} of "status is CREATED" and "status is absent", as the adapter builds it. */
  private static boolean matchesStatusClause(Document query, Purchase live) {
    Object or = query.get("$or");
    if (!(or instanceof List<?> branches)) {
      return true;
    }
    for (Object branch : branches) {
      Object clause = ((Document) branch).get("status");
      if (clause instanceof Document exists && exists.containsKey("$exists")) {
        if (live.getStatus() == null) {
          return true;
        }
      } else if (Objects.equals(clause, live.getStatus())) {
        return true;
      }
    }
    return false;
  }

  private static void apply(Purchase live, String field, Object value) {
    switch (field) {
      case "subTotal" -> live.setSubTotal((BigDecimal) value);
      case "taxTotal" -> live.setTaxTotal((BigDecimal) value);
      case "sgstAmount" -> live.setSgstAmount((BigDecimal) value);
      case "cgstAmount" -> live.setCgstAmount((BigDecimal) value);
      case "discountTotal" -> live.setDiscountTotal((BigDecimal) value);
      case "saleAdditionalDiscountTotal" -> live.setSaleAdditionalDiscountTotal((BigDecimal) value);
      case "grandTotal" -> live.setGrandTotal((BigDecimal) value);
      case "totalCost" -> live.setTotalCost((BigDecimal) value);
      case "revenueBeforeTax" -> live.setRevenueBeforeTax((BigDecimal) value);
      case "revenueAfterTax" -> live.setRevenueAfterTax((BigDecimal) value);
      case "totalProfit" -> live.setTotalProfit((BigDecimal) value);
      case "marginPercent" -> live.setMarginPercent((BigDecimal) value);
      case "updatedAt" -> live.setUpdatedAt((java.time.Instant) value);
      default -> throw new AssertionError("The recompute wrote an unexpected field: " + field);
    }
  }

  private static Purchase openBill() {
    Purchase bill = new Purchase();
    bill.setId(BILL_ID);
    bill.setShopId(SHOP_ID);
    bill.setStatus(PurchaseStatus.CREATED);
    bill.setBillingMode(BillingMode.REGULAR);
    bill.setItems(new ArrayList<>());
    bill.setGrandTotal(BigDecimal.ZERO);
    return bill;
  }

  private static PurchaseItem menuLine(
      String lineRef, String name, int quantity, String price, String cgst, String sgst) {
    PurchaseItem item = new PurchaseItem();
    item.setLineRef(lineRef);
    item.setName(name);
    item.setSellMode("menu");
    item.setBillingMode(BillingMode.REGULAR);
    item.setQuantity(BigDecimal.valueOf(quantity));
    item.setBaseQuantity(quantity);
    item.setUnitFactor(1);
    item.setSaleUnit("PCS");
    item.setMaximumRetailPrice(new BigDecimal(price));
    item.setPriceToRetail(new BigDecimal(price));
    item.setDiscount(BigDecimal.ZERO);
    item.setCgst(cgst);
    item.setSgst(sgst);
    return item;
  }

  /** A read returns a document of its own; the store is only reachable through a write. */
  private static Purchase copy(Purchase source) {
    Purchase copy = new Purchase();
    copy.setId(source.getId());
    copy.setShopId(source.getShopId());
    copy.setStatus(source.getStatus());
    copy.setBillingMode(source.getBillingMode());
    copy.setGrandTotal(source.getGrandTotal());
    copy.setCafeKotCancels(source.getCafeKotCancels());
    List<PurchaseItem> items = new ArrayList<>();
    source.getItems().forEach(item -> items.add(copyItem(item)));
    copy.setItems(items);
    return copy;
  }

  private static PurchaseItem copyItem(PurchaseItem source) {
    PurchaseItem copy = new PurchaseItem();
    copy.setLineRef(source.getLineRef());
    copy.setName(source.getName());
    copy.setSellMode(source.getSellMode());
    copy.setBillingMode(source.getBillingMode());
    copy.setQuantity(source.getQuantity());
    copy.setBaseQuantity(source.getBaseQuantity());
    copy.setUnitFactor(source.getUnitFactor());
    copy.setSaleUnit(source.getSaleUnit());
    copy.setMaximumRetailPrice(source.getMaximumRetailPrice());
    copy.setPriceToRetail(source.getPriceToRetail());
    copy.setDiscount(source.getDiscount());
    copy.setCgst(source.getCgst());
    copy.setSgst(source.getSgst());
    copy.setKotSentQuantity(source.getKotSentQuantity());
    return copy;
  }
}
