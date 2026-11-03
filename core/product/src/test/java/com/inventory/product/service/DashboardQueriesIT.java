package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.service.DashboardQueries.DayTotal;
import com.inventory.product.service.DashboardQueries.LowLot;
import com.inventory.product.service.DashboardQueries.SalesSummary;
import com.inventory.product.service.DashboardQueries.StockSummary;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

/**
 * The two dashboard aggregations against a real MongoDB, checked against the old in-memory rules
 * (re-implemented here over the same raw documents, with India days).
 *
 * <pre>
 *   docker run -d --rm --name stockkart-test-mongo -p 27018:27017 --tmpfs /data/db mongo:7
 *   mvn -pl core/product -am test -Dtest='DashboardQueriesIT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27018/stockkart_test
 * </pre>
 * Seeds edge cases on purpose: sales just after midnight IST (still the previous UTC day), future
 * dates, null totals, blank customers, other statuses and shops; lots with no base count, .5 counts
 * (half-up, not half-to-even), pack factors of 0, negative stock, blank and repeated lot ids.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class DashboardQueriesIT {

  private static final String SHOP = "dash-shop";
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final LocalDate TODAY = LocalDate.of(2026, 3, 2);
  private static final Instant WEEK_START = TODAY.minusDays(6).atStartOfDay(IST).toInstant();
  private static final Instant MONTH_START = TODAY.withDayOfMonth(1).atStartOfDay(IST).toInstant();
  private static final Instant TODAY_START = TODAY.atStartOfDay(IST).toInstant();
  private static final Instant TODAY_END = TODAY.plusDays(1).atStartOfDay(IST).toInstant();

  private static MongoClient client;
  private static MongoTemplate template;
  private static DashboardQueries queries;
  private static final List<Document> sales = new ArrayList<>();
  private static final List<Document> lots = new ArrayList<>();
  private static final Map<String, Document> PRODUCTS = new HashMap<>();

  @BeforeAll
  static void seed() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0];
    template = new MongoTemplate(client, db);
    template.getDb().drop();
    queries = new DashboardQueries(template, new MongoRepositoryFactory(template).getRepository(ProductRepository.class));

    Random rnd = new Random(7);
    String[] statuses = {"COMPLETED", "COMPLETED", "COMPLETED", "CREATED", "CANCELLED"};
    String[] customers = {null, "", "   ", "c1", "c2", "c3", "c4", " c5 "};
    for (int i = 0; i < 3_000; i++) {
      // spread over ~90 days around "today", plus a few in the future
      Instant soldAt = TODAY_END.minusSeconds(rnd.nextInt(90 * 86_400)).plusSeconds(rnd.nextInt(10) == 0 ? 3 * 86_400 : 0);
      sales.add(sale(i % 50 == 0 ? "other-shop" : SHOP, statuses[rnd.nextInt(statuses.length)], soldAt,
          i % 97 == 0 ? null : new BigDecimal(rnd.nextInt(100_000)).movePointLeft(2), customers[rnd.nextInt(customers.length)]));
    }
    // midnight edges: 00:00 and 00:10 IST today, 23:59:59 IST yesterday
    LocalDateTime midnight = TODAY.atStartOfDay();
    sales.add(sale(SHOP, "COMPLETED", midnight.atZone(IST).toInstant(), new BigDecimal("11.10"), "c1"));
    sales.add(sale(SHOP, "COMPLETED", midnight.plusMinutes(10).atZone(IST).toInstant(), new BigDecimal("22.20"), null));
    sales.add(sale(SHOP, "COMPLETED", midnight.minusSeconds(1).atZone(IST).toInstant(), new BigDecimal("33.30"), "c9"));
    template.getCollection("purchases").insertMany(sales);

    Object[][] counts = {
      // currentBaseCount, currentCount, factor
      {5, new BigDecimal("99"), null},
      {null, new BigDecimal("2.5"), null}, // half-up → 3 (half-to-even would give 2)
      {null, new BigDecimal("3.5"), 2}, // 7
      {null, new BigDecimal("4.25"), 0}, // factor 0 → 1 → 4
      {null, new BigDecimal("-2.5"), null}, // → -3
      {null, null, null}, // nothing → 0
      {0, new BigDecimal("0"), null},
      {10, new BigDecimal("10"), null},
      {11, new BigDecimal("11"), null},
      {null, new BigDecimal("10.4"), null}, // 10
      {null, new BigDecimal("10.5"), null}, // 11
      {null, new BigDecimal("6"), 2}, // 12 with the product's factor: not low (6 would be)
    };
    String[] lotIds = {"L1", "L2", "L1", " ", "", null, "L3"};
    // name, barcode and pack factor live on the product; the lot's own copies are stale and ignored
    List<Document> products = new ArrayList<>();
    for (int p = 0; p < 30; p++) {
      Document prod = new Document("_id", new ObjectId()).append("shopId", SHOP).append("name", "Product " + p);
      if (p % 4 != 0) {
        prod.append("barcode", "P" + p);
      }
      products.add(prod);
    }
    template.getCollection("product").insertMany(products);
    for (int i = 0; i < 400; i++) {
      Object[] c = counts[i % counts.length];
      Document lot = new Document("_id", new ObjectId())
          .append("shopId", i % 40 == 0 ? "other-shop" : SHOP)
          .append("name", "stale lot name " + i)
          .append("barcode", "stale" + i)
          .append("unitConversions", new Document("factor", 5));
      if (i % 13 == 0) {
        lot.append("productId", new ObjectId().toHexString()); // product deleted
      } else if (i % 7 != 0) { // every 7th lot has no product (legacy)
        Document prod = products.get(i % 30); // the plain products (no pack factor)
        lot.append("productId", prod.getObjectId("_id").toHexString());
        if (c[2] != null) { // a pack factor: give this lot its own product carrying it
          prod = new Document("_id", new ObjectId()).append("shopId", SHOP).append("name", "Packed " + i)
              .append("barcode", "PK" + i).append("unitConversions", new Document("unit", "STRIP").append("factor", c[2]));
          template.getCollection("product").insertOne(prod);
          products.add(prod);
          lot.put("productId", prod.getObjectId("_id").toHexString());
        }
      }
      String lotId = i % 5 == 0 ? lotIds[i % lotIds.length] : "LOT-" + (i % 150);
      if (lotId != null) {
        lot.append("lotId", lotId);
      }
      if (c[0] != null) {
        lot.append("currentBaseCount", c[0]);
      }
      if (c[1] != null) {
        lot.append("currentCount", new Decimal128((BigDecimal) c[1]));
      }
      if (i % 9 != 0) { // some lots have no createdAt (legacy)
        lot.append("createdAt", Date.from(TODAY_END.minusSeconds(rnd.nextInt(45 * 86_400))));
      }
      lots.add(lot);
    }
    for (Document p : products) {
      PRODUCTS.put(p.getObjectId("_id").toHexString(), p);
    }
    lots.add(new Document("_id", new ObjectId()).append("shopId", SHOP).append("name", "today edge")
        .append("currentBaseCount", 50).append("createdAt", Date.from(TODAY_START)));
    lots.add(new Document("_id", new ObjectId()).append("shopId", SHOP).append("name", "tomorrow")
        .append("currentBaseCount", 50).append("createdAt", Date.from(TODAY_END)));
    template.getCollection("inventory").insertMany(lots);
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

  private static Document sale(String shop, String status, Instant soldAt, BigDecimal total, String customer) {
    Document d = new Document("_id", new ObjectId()).append("shopId", shop).append("status", status)
        .append("soldAt", Date.from(soldAt));
    if (total != null) {
      d.append("grandTotal", new Decimal128(total));
    }
    if (customer != null) {
      d.append("customerId", customer);
    }
    return d;
  }

  // --- the old rules, over raw documents ---

  private static List<Document> completed() {
    return sales.stream().filter(s -> SHOP.equals(s.get("shopId")) && "COMPLETED".equals(s.get("status"))).toList();
  }

  private static BigDecimal total(Document s) {
    Object v = s.get("grandTotal");
    return v == null ? BigDecimal.ZERO : ((Decimal128) v).bigDecimalValue();
  }

  /** The lot's product as the read aspect would hydrate it (null: no product or product gone). */
  private static Document product(Document lot) {
    String pid = lot.getString("productId");
    return pid == null ? null : PRODUCTS.get(pid);
  }

  private static int baseCount(Document lot) {
    if (lot.get("currentBaseCount") != null) {
      return lot.getInteger("currentBaseCount");
    }
    Object c = lot.get("currentCount");
    if (c == null) {
      return 0;
    }
    int factor = 1;
    Document p = product(lot);
    Document uc = p == null ? null : p.get("unitConversions", Document.class);
    if (uc != null && uc.getInteger("factor") != null && uc.getInteger("factor") > 0) {
      factor = uc.getInteger("factor");
    }
    return ((Decimal128) c).bigDecimalValue().multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.HALF_UP).intValue();
  }

  private static List<Document> shopLots() {
    return lots.stream().filter(l -> SHOP.equals(l.get("shopId"))).toList();
  }

  @Test
  void salesTotalsMatchTheOldRules() {
    SalesSummary s = queries.sales(SHOP, WEEK_START, IST);

    List<Document> done = completed();
    assertEquals(done.size(), s.ordersAllTime());
    assertEquals(0, done.stream().map(DashboardQueriesIT::total).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(s.revenueAllTime()));
    Set<String> customers = new LinkedHashSet<>();
    for (Document d : done) {
      String c = d.getString("customerId");
      if (c != null && !c.trim().isEmpty()) {
        customers.add(c);
      }
    }
    assertEquals(customers.size(), s.customers());

    Map<LocalDate, BigDecimal> revenue = new HashMap<>();
    Map<LocalDate, Long> orders = new HashMap<>();
    for (Document d : done) {
      Instant at = d.getDate("soldAt").toInstant();
      if (!at.isBefore(WEEK_START)) {
        LocalDate day = at.atZone(IST).toLocalDate();
        revenue.merge(day, total(d), BigDecimal::add);
        orders.merge(day, 1L, Long::sum);
      }
    }
    assertEquals(revenue.keySet(), s.days().keySet());
    for (Map.Entry<LocalDate, DayTotal> e : s.days().entrySet()) {
      assertEquals(0, revenue.get(e.getKey()).compareTo(e.getValue().revenue()), "revenue " + e.getKey());
      assertEquals(orders.get(e.getKey()), e.getValue().orders(), "orders " + e.getKey());
    }
    // the midnight edges land on the India day
    assertEquals(0, s.days().get(TODAY).revenue().compareTo(revenue.get(TODAY)));
  }

  @Test
  void stockNumbersMatchTheOldRules() {
    StockSummary s = queries.stock(SHOP, TODAY_START, TODAY_END, WEEK_START, MONTH_START, 10, 20);

    List<Document> mine = shopLots();
    assertEquals(
        mine.stream().map(l -> l.getString("lotId")).filter(id -> id != null && !id.trim().isEmpty()).distinct().count(),
        s.distinctLotIds());
    assertEquals(mine.stream().filter(l -> baseCount(l) <= 10).count(), s.lowStock());
    assertEquals(mine.stream().filter(l -> baseCount(l) == 0).count(), s.outOfStock());
    assertEquals(mine.stream().filter(l -> createdIn(l, TODAY_START, TODAY_END)).count(), s.addedToday());
    assertEquals(mine.stream().filter(l -> createdIn(l, WEEK_START, null)).count(), s.addedThisWeek());
    assertEquals(mine.stream().filter(l -> createdIn(l, MONTH_START, null)).count(), s.addedThisMonth());

    List<Document> expected = mine.stream().filter(l -> baseCount(l) <= 10)
        .sorted(Comparator.comparingInt(DashboardQueriesIT::baseCount).thenComparing(l -> l.getObjectId("_id")))
        .limit(20).toList();
    assertEquals(20, s.lowest().size());
    for (int i = 0; i < expected.size(); i++) {
      Document want = expected.get(i);
      LowLot got = s.lowest().get(i);
      assertEquals(want.getObjectId("_id").toHexString(), got.id());
      assertEquals(baseCount(want), got.baseCount());
      Document p = product(want);
      assertEquals(p == null ? null : p.getString("name"), got.name());
      assertEquals(want.getString("lotId"), got.lotId());
      assertEquals(p == null ? null : p.getString("barcode"), got.barcode());
    }
    assertEquals(-3, s.lowest().get(0).baseCount()); // -2.5 rounds away from zero
    // the list really mixes the cases: product names, no product, and products without barcode
    assertTrue(s.lowest().stream().anyMatch(l -> l.name() == null));
    assertTrue(s.lowest().stream().anyMatch(l -> l.name() != null && l.barcode() == null));
    assertTrue(s.lowest().stream().noneMatch(l -> l.name() != null && l.name().startsWith("stale")));
  }

  @Test
  void halfCountsRoundUpLikeTheOldCode() {
    StockSummary s = queries.stock(SHOP, TODAY_START, TODAY_END, WEEK_START, MONTH_START, 2, 1_000);
    // 2.5 → 3 is not low at threshold 2; with half-to-even it would be 2 and counted
    assertEquals(shopLots().stream().filter(l -> baseCount(l) <= 2).count(), s.lowStock());
    assertEquals(s.lowStock(), s.lowest().size());
  }

  @Test
  void emptyShopGivesZeros() {
    SalesSummary s = queries.sales("nobody", WEEK_START, IST);
    assertEquals(0, s.ordersAllTime());
    assertEquals(BigDecimal.ZERO, s.revenueAllTime());
    assertEquals(0, s.customers());
    assertEquals(Map.of(), s.days());
    StockSummary k = queries.stock("nobody", TODAY_START, TODAY_END, WEEK_START, MONTH_START, 10, 20);
    assertEquals(new StockSummary(0, 0, 0, 0, 0, 0, List.of()), k);
  }

  private static boolean createdIn(Document lot, Instant from, Instant toExclusive) {
    Date d = lot.getDate("createdAt");
    if (d == null) {
      return false;
    }
    Instant at = d.toInstant();
    return !at.isBefore(from) && (toExclusive == null || at.isBefore(toExclusive));
  }
}
