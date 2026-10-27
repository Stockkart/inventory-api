package com.inventory.analytics.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.analytics.utils.AnalyticsSalesQueries.BillTotals;
import com.inventory.analytics.utils.AnalyticsSalesQueries.BucketTotal;
import com.inventory.analytics.utils.AnalyticsSalesQueries.CustomerTotal;
import com.inventory.analytics.utils.AnalyticsSalesQueries.LineGroup;
import com.inventory.product.config.PurchaseBackwardCompatCallback;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.repository.InventoryRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@link AnalyticsSalesQueries} against a real MongoDB, checked against the old way of reading:
 * every bill loaded as a {@link Purchase} entity (same BigDecimal↔Decimal128 conversions as the app,
 * plus {@link PurchaseBackwardCompatCallback} for legacy {@code sellingPrice}), then summed line by
 * line in Java.
 *
 * <pre>
 *   docker run -d --rm --name stockkart-test-mongo -p 27018:27017 --tmpfs /data/db mongo:7
 *   mvn -pl core/analytics -am test -Dtest='AnalyticsSalesQueriesIT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27018/stockkart_test
 * </pre>
 * The seed mixes everything seen in dev/prod data: int and decimal quantities, missing base
 * quantity or factor, factor 0, legacy {@code sellingPrice} without {@code priceToRetail}, legacy
 * {@code inventoryId} lines, {@code sellableRef}/{@code stockRef} lines, menu and direct-menu lines,
 * lines with no reference at all (migrated sales), bills without items, other statuses and shops,
 * bills exactly on the window's edges and just after IST midnight, customers by id, by typed name,
 * both and neither.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class AnalyticsSalesQueriesIT {

  private static final String SHOP = "an-shop";
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final Instant START = LocalDateTime.of(2026, 9, 1, 0, 0).atZone(IST).toInstant();
  private static final Instant END = LocalDateTime.of(2026, 9, 30, 23, 59, 59).atZone(IST).toInstant();
  private static final String[] LOTS = {
    new ObjectId().toHexString(), new ObjectId().toHexString(), new ObjectId().toHexString(), new ObjectId().toHexString()
  };

  private static MongoClient client;
  private static MongoTemplate template;
  private static AnalyticsSalesQueries queries;
  /** The shop's completed bills as the old code saw them. */
  private static List<Purchase> entities;

  @BeforeAll
  static void seed() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0];
    template = appLikeTemplate(client, db);
    template.getDb().drop();
    InventoryRepository inventories = mock(InventoryRepository.class);
    when(inventories.findAllById(any())).thenReturn(List.of());
    queries = new AnalyticsSalesQueries(template, inventories);

    Random rnd = new Random(11);
    List<Document> bills = new ArrayList<>();
    String[] statuses = {"COMPLETED", "COMPLETED", "COMPLETED", "CREATED", "CANCELLED"};
    for (int i = 0; i < 1_500; i++) {
      // ~45 days around the window, so some bills fall outside on both sides
      Instant soldAt = START.minusSeconds(7 * 86_400L).plusSeconds(rnd.nextInt(45 * 86_400));
      Document bill = bill(i % 30 == 0 ? "other-shop" : SHOP, statuses[rnd.nextInt(statuses.length)], soldAt, rnd);
      if (i % 41 != 0) { // some bills have no items
        List<Document> items = new ArrayList<>();
        int n = 1 + rnd.nextInt(4);
        for (int j = 0; j < n; j++) {
          items.add(line(rnd));
        }
        bill.append("items", items);
      }
      bills.add(bill);
    }
    // edges: exactly at start and end (inside, both inclusive), 1 ms outside, just after IST midnight
    bills.add(bill(SHOP, "COMPLETED", START, rnd).append("items", List.of(line(rnd))));
    bills.add(bill(SHOP, "COMPLETED", END, rnd).append("items", List.of(line(rnd))));
    bills.add(bill(SHOP, "COMPLETED", START.minusMillis(1), rnd).append("items", List.of(line(rnd))));
    bills.add(bill(SHOP, "COMPLETED", END.plusMillis(1), rnd).append("items", List.of(line(rnd))));
    bills.add(bill(SHOP, "COMPLETED", LocalDateTime.of(2026, 9, 10, 0, 5).atZone(IST).toInstant(), rnd)
        .append("items", List.of(line(rnd))));
    template.getCollection("purchases").insertMany(bills);

    entities = template.find(Query.query(Criteria.where("shopId").is(SHOP).and("status").is("COMPLETED")), Purchase.class);
    Map<Object, Document> raw = new HashMap<>();
    for (Document d : bills) {
      raw.put(d.getObjectId("_id").toHexString(), d);
    }
    PurchaseBackwardCompatCallback compat = new PurchaseBackwardCompatCallback();
    for (Purchase p : entities) {
      compat.onAfterConvert(p, raw.get(p.getId()), "purchases");
    }
  }

  @AfterAll
  static void tearDown() {
    if (template != null) {
      template.getDb().drop();
    }
    if (client != null) {
      client.close();
    }
  }

  /** A template converting BigDecimal ↔ Decimal128 like the app's MongoConfig. */
  private static MongoTemplate appLikeTemplate(MongoClient client, String db) {
    SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(client, db);
    MongoCustomConversions conversions =
        new MongoCustomConversions(List.of(new ToDecimal128(), new FromDecimal128()));
    MongoMappingContext context = new MongoMappingContext();
    context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    context.afterPropertiesSet();
    MappingMongoConverter converter = new MappingMongoConverter(new DefaultDbRefResolver(factory), context);
    converter.setCustomConversions(conversions);
    converter.afterPropertiesSet();
    return new MongoTemplate(factory, converter);
  }

  /** As the app's MongoConfig: BigDecimal is stored as Decimal128. */
  static class ToDecimal128 implements Converter<BigDecimal, Decimal128> {
    @Override
    public Decimal128 convert(BigDecimal source) {
      return Decimal128.parse(source.toString());
    }
  }

  static class FromDecimal128 implements Converter<Decimal128, BigDecimal> {
    @Override
    public BigDecimal convert(Decimal128 source) {
      return source.bigDecimalValue();
    }
  }

  private static Document bill(String shop, String status, Instant soldAt, Random rnd) {
    Document d = new Document("_id", new ObjectId()).append("shopId", shop).append("status", status)
        .append("soldAt", Date.from(soldAt));
    if (rnd.nextInt(20) != 0) {
      d.append("grandTotal", dec(rnd.nextInt(500_000), 2)).append("taxTotal", dec(rnd.nextInt(50_000), 2));
    }
    if (rnd.nextBoolean()) {
      d.append("discountTotal", dec(rnd.nextInt(5_000), 2));
    }
    switch (rnd.nextInt(5)) {
      case 0 -> d.append("customerId", "c" + rnd.nextInt(8)).append("customerName", "Typed " + rnd.nextInt(3));
      case 1 -> d.append("customerId", "c" + rnd.nextInt(8));
      case 2 -> d.append("customerName", rnd.nextBoolean() ? "Walk-in " + rnd.nextInt(4) : "");
      case 3 -> d.append("customerId", "");
      default -> { } // neither
    }
    return d;
  }

  private static Decimal128 dec(long unscaled, int scale) {
    return new Decimal128(BigDecimal.valueOf(unscaled, scale));
  }

  private static Document line(Random rnd) {
    String lot = LOTS[rnd.nextInt(LOTS.length)];
    Document l = new Document("name", rnd.nextInt(6) == 0 ? "Renamed" : "Item " + lot.substring(20));
    switch (rnd.nextInt(6)) {
      case 0 -> l.append("inventoryId", lot); // legacy line
      case 1, 2 -> l.append("sellableRef", "inventory:" + lot).append("stockRef", "inventory:" + lot).append("sellMode", "sku");
      case 3 -> l.append("sellableRef", "menu:m" + rnd.nextInt(3)).append("sellMode", "menu"); // no stock
      case 4 -> l.append("menuItemId", "m1").append("inventoryId", lot).append("sellMode", "direct"); // legacy direct
      default -> l.append("sellMode", "sku"); // migrated line: no reference at all
    }
    switch (rnd.nextInt(4)) {
      case 0 -> l.append("quantity", 1 + rnd.nextInt(3)); // int
      case 1 -> l.append("quantity", dec(5 + rnd.nextInt(30), 1)); // 0.5 .. 3.4
      case 2 -> l.append("quantity", dec(1 + rnd.nextInt(3), 0)).append("baseQuantity", 10 * (1 + rnd.nextInt(3)));
      default -> l.append("baseQuantity", 1 + rnd.nextInt(25)); // no quantity: pricing quantity from base ÷ factor
    }
    switch (rnd.nextInt(4)) {
      case 0 -> l.append("unitFactor", 10);
      case 1 -> l.append("unitFactor", 0);
      case 2 -> l.append("unitFactor", 3); // base ÷ 3 needs the 4-dp half-up rounding
      default -> { }
    }
    switch (rnd.nextInt(4)) {
      case 0 -> l.append("priceToRetail", dec(1000 + rnd.nextInt(3) * 250, 2));
      case 1 -> l.append("sellingPrice", dec(999 + rnd.nextInt(2), 2)); // legacy price only
      case 2 -> l.append("sellingPrice", dec(1, 2)).append("priceToRetail", dec(1200, 2)); // priceToRetail wins
      default -> { } // no price
    }
    if (rnd.nextBoolean()) {
      l.append("discount", dec(rnd.nextInt(3) * 50, 2));
    }
    if (rnd.nextBoolean()) {
      l.append("maximumRetailPrice", dec(1500, 2));
    }
    return l;
  }

  // --- the old per-line rules (copied from the analytics utils as they were) ---

  private static int oldBase(PurchaseItem item) {
    if (item.getBaseQuantity() != null) {
      return item.getBaseQuantity();
    }
    if (item.getQuantity() == null) {
      return 0;
    }
    int factor = item.getUnitFactor() != null && item.getUnitFactor() > 0 ? item.getUnitFactor() : 1;
    return item.getQuantity().multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.HALF_UP).intValue();
  }

  private static BigDecimal oldPricing(PurchaseItem item) {
    if (item.getQuantity() != null) {
      return item.getQuantity();
    }
    if (item.getBaseQuantity() != null) {
      int factor = item.getUnitFactor() != null && item.getUnitFactor() > 0 ? item.getUnitFactor() : 1;
      return BigDecimal.valueOf(item.getBaseQuantity()).divide(BigDecimal.valueOf(factor), 4, RoundingMode.HALF_UP);
    }
    return BigDecimal.ZERO;
  }

  private static BigDecimal oldRevenue(PurchaseItem item) {
    return item.getPriceToRetail() != null ? item.getPriceToRetail().multiply(oldPricing(item)) : BigDecimal.ZERO;
  }

  private static List<Purchase> inWindow() {
    return entities.stream().filter(p -> !p.getSoldAt().isBefore(START) && !p.getSoldAt().isAfter(END)).toList();
  }

  /** Per-key sums, old way (line by line) and new way (groups × count). */
  private record Sums(long lines, long base, BigDecimal revenue, BigDecimal pricing, BigDecimal discount, BigDecimal mrp) {
    static Sums zero() {
      return new Sums(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    Sums plus(long n, long base, BigDecimal revenue, BigDecimal pricing, BigDecimal discount, BigDecimal mrp) {
      return new Sums(lines + n, this.base + base, this.revenue.add(revenue), this.pricing.add(pricing),
          this.discount.add(discount), this.mrp.add(mrp));
    }

    void assertSame(Sums o, String what) {
      assertEquals(lines, o.lines, what + " lines");
      assertEquals(base, o.base, what + " base qty");
      assertEquals(0, revenue.compareTo(o.revenue), what + " revenue " + revenue + " vs " + o.revenue);
      assertEquals(0, pricing.compareTo(o.pricing), what + " pricing qty");
      assertEquals(0, discount.compareTo(o.discount), what + " discount");
      assertEquals(0, mrp.compareTo(o.mrp), what + " mrp revenue");
    }
  }

  private static Map<String, Sums> oldSums(Function<PurchaseItem, String> keyOf, Function<Purchase, String> billKey) {
    Map<String, Sums> out = new HashMap<>();
    for (Purchase p : inWindow()) {
      if (p.getItems() == null) {
        continue;
      }
      for (PurchaseItem item : p.getItems()) {
        BigDecimal price = item.getPriceToRetail() != null ? item.getPriceToRetail() : BigDecimal.ZERO;
        BigDecimal disc = item.getDiscount() != null ? item.getDiscount() : BigDecimal.ZERO;
        BigDecimal mrp = item.getMaximumRetailPrice() != null ? item.getMaximumRetailPrice() : price;
        String key = keyOf.apply(item) + "|" + billKey.apply(p);
        out.merge(key, Sums.zero().plus(1, oldBase(item), oldRevenue(item), oldPricing(item),
            disc.multiply(oldPricing(item)), mrp.multiply(oldPricing(item))), (a, b) ->
            a.plus(b.lines, b.base, b.revenue, b.pricing, b.discount, b.mrp));
      }
    }
    return out;
  }

  private static Map<String, Sums> newSums(List<LineGroup> groups, Function<PurchaseItem, String> keyOf, Function<LineGroup, String> bucketKey) {
    Map<String, Sums> out = new HashMap<>();
    for (LineGroup g : groups) {
      BigDecimal price = g.line().getPriceToRetail() != null ? g.line().getPriceToRetail() : BigDecimal.ZERO;
      BigDecimal disc = g.line().getDiscount() != null ? g.line().getDiscount() : BigDecimal.ZERO;
      BigDecimal mrp = g.line().getMaximumRetailPrice() != null ? g.line().getMaximumRetailPrice() : price;
      String key = keyOf.apply(g.line()) + "|" + bucketKey.apply(g);
      out.merge(key, Sums.zero().plus(g.lines(), g.times(g.baseQuantity()), g.times(SalesAnalyticsUtils.lineRevenue(g)),
          g.times(g.pricingQuantity()), g.times(disc.multiply(g.pricingQuantity())), g.times(mrp.multiply(g.pricingQuantity()))),
          (a, b) -> a.plus(b.lines, b.base, b.revenue, b.pricing, b.discount, b.mrp));
    }
    return out;
  }

  private static void assertSameSums(Map<String, Sums> old, Map<String, Sums> neu) {
    assertEquals(old.keySet(), neu.keySet());
    old.forEach((k, v) -> v.assertSame(neu.get(k), k));
  }

  // --- tests ---

  @Test
  void groupedLinesAddUpToTheOldLineByLineSums() {
    List<LineGroup> groups = queries.lineGroups(SHOP, START, END, null, false);
    long lines = inWindow().stream().mapToLong(p -> p.getItems() == null ? 0 : p.getItems().size()).sum();
    assertTrue(groups.size() < lines, "identical lines are grouped: " + groups.size() + " groups for " + lines + " lines");
    // by lot (incl. null: menu and reference-less lines), by line name
    assertSameSums(oldSums(i -> String.valueOf(i.getInventoryId()), p -> ""), newSums(groups, i -> String.valueOf(i.getInventoryId()), g -> ""));
    assertSameSums(oldSums(i -> String.valueOf(i.getName()), p -> ""), newSums(groups, i -> String.valueOf(i.getName()), g -> ""));
  }

  @Test
  void bucketedLinesMatchIndiaDaysAndHours() {
    assertSameSums(
        oldSums(i -> String.valueOf(i.getInventoryId()), p -> p.getSoldAt().atZone(IST).toLocalDate().toString()),
        newSums(queries.lineGroups(SHOP, START, END, "day", false), i -> String.valueOf(i.getInventoryId()), LineGroup::bucket));
    assertSameSums(
        oldSums(i -> "", p -> LocalDateTime.ofInstant(p.getSoldAt(), IST).truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString()),
        newSums(queries.lineGroups(SHOP, START, END, "hour", false), i -> "", LineGroup::bucket));
    // week / month: MongoDB returns the week's Monday / month's 1st; the chart key is the old one
    assertSameSums(
        oldSums(i -> String.valueOf(i.getInventoryId()),
            p -> p.getSoldAt().atZone(IST).toLocalDate().with(java.time.DayOfWeek.MONDAY) + "_week"),
        newSums(queries.lineGroups(SHOP, START, END, "week", false), i -> String.valueOf(i.getInventoryId()),
            g -> AnalyticsPeriods.keyOf(g.bucket(), "week")));
    // the window starts at 1 Sep 00:00 IST (31 Aug in UTC): a UTC month would put those bills in August
    assertSameSums(
        oldSums(i -> "", p -> p.getSoldAt().atZone(IST).toLocalDate().toString().substring(0, 7)),
        newSums(queries.lineGroups(SHOP, START, END, "month", false), i -> "",
            g -> AnalyticsPeriods.keyOf(g.bucket(), "month")));
    assertTrue(queries.lineGroups(SHOP, START, END, "week", false).size()
        <= queries.lineGroups(SHOP, START, END, "day", false).size(), "weekly charts never need more groups than daily");
  }

  @Test
  void billIdsAndLatestSaleOfEachGroup() {
    Map<String, Set<String>> oldBills = new HashMap<>();
    Map<String, Instant> oldLast = new HashMap<>();
    for (Purchase p : inWindow()) {
      if (p.getItems() != null) {
        for (PurchaseItem item : p.getItems()) {
          String lot = String.valueOf(item.getInventoryId());
          oldBills.computeIfAbsent(lot, k -> new HashSet<>()).add(p.getId());
          oldLast.merge(lot, p.getSoldAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
      }
    }
    Map<String, Set<String>> newBills = new HashMap<>();
    Map<String, Instant> newLast = new HashMap<>();
    for (LineGroup g : queries.lineGroups(SHOP, START, END, null, true)) {
      String lot = String.valueOf(g.lotId());
      newBills.computeIfAbsent(lot, k -> new HashSet<>()).addAll(g.billIds());
      newLast.merge(lot, g.lastSoldAt(), (a, b) -> a.isAfter(b) ? a : b);
    }
    assertEquals(oldBills, newBills);
    assertEquals(oldLast, newLast);
  }

  @Test
  void billTotalsAndBucketsMatch() {
    List<Purchase> bills = inWindow();
    BillTotals t = queries.billTotals(SHOP, START, END);
    assertEquals(bills.size(), t.bills());
    assertEquals(0, sum(bills, Purchase::getGrandTotal).compareTo(t.grandTotal()));
    assertEquals(0, sum(bills, Purchase::getTaxTotal).compareTo(t.taxTotal()));
    assertEquals(0, sum(bills, Purchase::getDiscountTotal).compareTo(t.discountTotal()));
    assertEquals(new BillTotals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), queries.billTotals("nobody", START, END));

    Map<String, BigDecimal> oldDays = new HashMap<>();
    Map<String, Long> oldCounts = new HashMap<>();
    for (Purchase p : bills) {
      String day = p.getSoldAt().atZone(IST).toLocalDate().toString();
      oldDays.merge(day, p.getGrandTotal() != null ? p.getGrandTotal() : BigDecimal.ZERO, BigDecimal::add);
      oldCounts.merge(day, 1L, Long::sum);
    }
    List<BucketTotal> days = queries.bucketTotals(SHOP, START, END, "day");
    assertEquals(oldDays.size(), days.size());
    for (BucketTotal b : days) {
      assertEquals(0, oldDays.get(b.bucket()).compareTo(b.grandTotal()), b.bucket());
      assertEquals(oldCounts.get(b.bucket()), b.bills(), b.bucket());
    }
  }

  @Test
  void customerTotalsMatchTheOldGrouping() {
    record Old(String id, String name, int bills, BigDecimal total, Instant first, Instant last, int inPeriod) {}
    Map<String, Old> old = new HashMap<>();
    List<Purchase> all = entities.stream().sorted((a, b) -> a.getSoldAt().compareTo(b.getSoldAt())).toList();
    for (Purchase p : all) {
      String key;
      if (p.getCustomerId() != null) {
        key = "ID:" + p.getCustomerId();
      } else if (p.getCustomerName() != null) {
        key = "NAME:" + p.getCustomerName();
      } else {
        continue;
      }
      boolean in = !p.getSoldAt().isBefore(START) && !p.getSoldAt().isAfter(END);
      BigDecimal total = p.getGrandTotal() != null ? p.getGrandTotal() : BigDecimal.ZERO;
      Old o = old.get(key);
      // name for an id: the earliest typed name (no customer records in this test)
      old.put(key, o == null
          ? new Old(p.getCustomerId(), p.getCustomerName(), 1, total, p.getSoldAt(), p.getSoldAt(), in ? 1 : 0)
          : new Old(o.id, o.name != null ? o.name : p.getCustomerName(), o.bills + 1, o.total.add(total), o.first,
              p.getSoldAt(), o.inPeriod + (in ? 1 : 0)));
    }
    List<CustomerTotal> got = queries.customerTotals(SHOP, START, END);
    assertEquals(old.size(), got.size());
    for (CustomerTotal c : got) {
      Old o = old.get(c.customerId() != null ? "ID:" + c.customerId() : "NAME:" + c.billName());
      assertEquals(o.name, c.billName(), "name of " + c.customerId());
      assertEquals(o.bills, c.bills());
      assertEquals(0, o.total.compareTo(c.grandTotal()));
      assertEquals(o.first, c.firstSoldAt());
      assertEquals(o.last, c.lastSoldAt());
      assertEquals(o.inPeriod, c.billsInPeriod());
    }
    assertTrue(old.containsKey("ID:"), "empty customer id is an id, as before");
    assertTrue(old.containsKey("NAME:"), "empty typed name is a name, as before");
    assertEquals(entities.size(), queries.allTimeBills(SHOP));
  }

  @Test
  void lastSoldPerLotOverAllTime() {
    Map<String, Instant> old = new HashMap<>();
    for (Purchase p : entities) {
      if (p.getItems() != null) {
        for (PurchaseItem item : p.getItems()) {
          if (item.getInventoryId() != null) {
            old.merge(item.getInventoryId(), p.getSoldAt(), (a, b) -> a.isAfter(b) ? a : b);
          }
        }
      }
    }
    assertEquals(old, queries.lastSoldByLot(SHOP));
  }

  private static BigDecimal sum(List<Purchase> bills, Function<Purchase, BigDecimal> f) {
    return bills.stream().map(f).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
