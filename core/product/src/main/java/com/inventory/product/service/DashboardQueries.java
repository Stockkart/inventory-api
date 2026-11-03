package com.inventory.product.service;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.ProductRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The dashboard's numbers, added up inside MongoDB: one aggregation over the shop's completed sales
 * and one over its lots (plus one product read for the low-stock names). Only totals and a few rows
 * come back, so the cost no longer grows with the shop's history in the API's memory.
 */
@Component
public class DashboardQueries {

  /** A runaway aggregation must not hold a pool connection for minutes. */
  static final long MAX_TIME_MS = 15_000;

  private final MongoTemplate mongoTemplate;
  private final ProductRepository productRepository;

  public DashboardQueries(MongoTemplate mongoTemplate, ProductRepository productRepository) {
    this.mongoTemplate = mongoTemplate;
    this.productRepository = productRepository;
  }

  /** Revenue and order count of one calendar day (in the zone passed to {@link #sales}). */
  public record DayTotal(BigDecimal revenue, long orders) {}

  /**
   * @param revenueAllTime sum of {@code grandTotal} over all completed sales (nulls skipped)
   * @param ordersAllTime number of completed sales
   * @param customers distinct non-blank {@code customerId} among completed sales
   * @param days per-day totals for sales sold at or after the {@code daysFrom} passed in
   */
  public record SalesSummary(
      BigDecimal revenueAllTime, long ordersAllTime, long customers, Map<LocalDate, DayTotal> days) {}

  /** One row of the low-stock list. */
  public record LowLot(String id, String name, String lotId, String barcode, int baseCount) {}

  /**
   * @param distinctLotIds distinct non-blank {@code lotId} values
   * @param lowStock lots whose base count is at or below the threshold
   * @param outOfStock lots whose base count is exactly 0
   * @param lowest up to {@code topN} low-stock lots, lowest count first (ties by id)
   */
  public record StockSummary(
      long distinctLotIds,
      long lowStock,
      long outOfStock,
      long addedToday,
      long addedThisWeek,
      long addedThisMonth,
      List<LowLot> lowest) {}

  /** One aggregation: all-time totals, distinct customers and per-day totals since {@code daysFrom}. */
  public SalesSummary sales(String shopId, Instant daysFrom, ZoneId zone) {
    List<Document> pipeline =
        List.of(
            // served by the {shopId, status, soldAt} index
            new Document(
                "$match",
                new Document("shopId", shopId).append("status", PurchaseStatus.COMPLETED.name())),
            new Document(
                "$project",
                new Document("_id", 0).append("grandTotal", 1).append("soldAt", 1).append("customerId", 1)),
            new Document(
                "$facet",
                new Document(
                        "totals",
                        List.of(
                            new Document(
                                "$group",
                                new Document("_id", null)
                                    .append("revenue", new Document("$sum", "$grandTotal"))
                                    .append("orders", new Document("$sum", 1)))))
                    .append(
                        "customers",
                        List.of(
                            new Document("$match", new Document("customerId", new Document("$type", "string"))),
                            new Document("$match", new Document("$expr", nonBlank("$customerId"))),
                            new Document("$group", new Document("_id", "$customerId")),
                            new Document("$count", "n")))
                    .append(
                        "days",
                        List.of(
                            new Document("$match", new Document("soldAt", new Document("$gte", Date.from(daysFrom)))),
                            new Document(
                                "$group",
                                new Document(
                                        "_id",
                                        new Document(
                                            "$dateToString",
                                            new Document("format", "%Y-%m-%d")
                                                .append("date", "$soldAt")
                                                .append("timezone", zone.getId())))
                                    .append("revenue", new Document("$sum", "$grandTotal"))
                                    .append("orders", new Document("$sum", 1)))))));

    Document facet = aggregateOne(Purchase.class, pipeline);
    Document totals = first(facet, "totals");
    Map<LocalDate, DayTotal> days = new HashMap<>();
    for (Document d : facet.getList("days", Document.class, List.of())) {
      days.put(LocalDate.parse(d.getString("_id")), new DayTotal(decimal(d.get("revenue")), count(d.get("orders"))));
    }
    return new SalesSummary(
        totals == null ? BigDecimal.ZERO : decimal(totals.get("revenue")),
        totals == null ? 0 : count(totals.get("orders")),
        countOf(facet, "customers"),
        days);
  }

  /**
   * One aggregation over the shop's lots, plus one batched product read for the low-stock rows.
   *
   * <p>A lot's base count is {@code currentBaseCount}; lots without one (legacy) fall back to
   * {@code currentCount × factor} rounded half-up, else 0 — the same rule the old in-memory code
   * used. The factor, name and barcode are owned by the lot's {@link Product} (they are
   * {@code @Transient} on {@link Inventory}), so they come from the product: factor 1 and no
   * name/barcode when the lot has no product. Legacy lots are few, so their counts are finished here.
   *
   * <p>"Added" counts use {@code createdAt}: today is {@code [todayStart, todayEnd)}, this week and
   * this month are everything at or after {@code weekStart} / {@code monthStart}.
   */
  public StockSummary stock(
      String shopId,
      Instant todayStart,
      Instant todayEnd,
      Instant weekStart,
      Instant monthStart,
      int lowThreshold,
      int topN) {
    Document hasBase = new Document("$isNumber", "$currentBaseCount");
    Document lowBase =
        new Document("$and", List.of(hasBase, new Document("$lte", List.of("$currentBaseCount", lowThreshold))));
    Document zeroBase =
        new Document("$and", List.of(hasBase, new Document("$eq", List.of("$currentBaseCount", 0))));
    List<Document> pipeline =
        List.of(
            new Document("$match", new Document("shopId", shopId)),
            new Document(
                "$project",
                new Document("lotId", 1)
                    .append("productId", 1)
                    .append("createdAt", 1)
                    .append("currentBaseCount", 1)
                    .append("currentCount", 1)),
            new Document(
                "$facet",
                new Document(
                        "counts",
                        List.of(
                            new Document(
                                "$group",
                                new Document("_id", null)
                                    .append("low", sumIf(lowBase))
                                    .append("out", sumIf(zeroBase))
                                    .append(
                                        "today",
                                        sumIf(
                                            new Document(
                                                "$and",
                                                List.of(
                                                    gte("$createdAt", todayStart),
                                                    new Document("$lt", List.of("$createdAt", Date.from(todayEnd)))))))
                                    .append("week", sumIf(gte("$createdAt", weekStart)))
                                    .append("month", sumIf(gte("$createdAt", monthStart))))))
                    .append(
                        "lots",
                        List.of(
                            new Document("$match", new Document("lotId", new Document("$type", "string"))),
                            new Document("$match", new Document("$expr", nonBlank("$lotId"))),
                            new Document("$group", new Document("_id", "$lotId")),
                            new Document("$count", "n")))
                    .append(
                        "lowest",
                        List.of(
                            new Document("$match", new Document("$expr", lowBase)),
                            new Document("$sort", new Document("currentBaseCount", 1).append("_id", 1)),
                            new Document("$limit", topN),
                            new Document("$project", new Document("lotId", 1).append("productId", 1).append("currentBaseCount", 1))))
                    .append(
                        "noBase",
                        List.of(
                            new Document("$match", new Document("$expr", new Document("$not", List.of(hasBase)))),
                            new Document("$project", new Document("lotId", 1).append("productId", 1).append("currentCount", 1))))));

    Document facet = aggregateOne(Inventory.class, pipeline);
    List<Document> dbLowest = facet.getList("lowest", Document.class, List.of());
    List<Document> noBase = facet.getList("noBase", Document.class, List.of());
    Map<String, Product> products = products(dbLowest, noBase);

    record Row(String id, String lotId, String productId, int base) {}
    List<Row> candidates = new ArrayList<>();
    for (Document d : dbLowest) {
      candidates.add(new Row(id(d), d.getString("lotId"), productId(d), (int) count(d.get("currentBaseCount"))));
    }
    long legacyLow = 0;
    long legacyOut = 0;
    for (Document d : noBase) {
      int base = legacyBaseCount(d.get("currentCount"), products.get(productId(d)));
      if (base <= lowThreshold) {
        legacyLow++;
        candidates.add(new Row(id(d), d.getString("lotId"), productId(d), base));
      }
      if (base == 0) {
        legacyOut++;
      }
    }
    candidates.sort(Comparator.comparingInt(Row::base).thenComparing(Row::id));
    List<LowLot> lowest = new ArrayList<>();
    for (Row r : candidates.subList(0, Math.min(topN, candidates.size()))) {
      Product p = r.productId() == null ? null : products.get(r.productId());
      lowest.add(new LowLot(r.id(), p == null ? null : p.getName(), r.lotId(), p == null ? null : p.getBarcode(), r.base()));
    }

    Document counts = first(facet, "counts");
    return new StockSummary(
        countOf(facet, "lots"),
        (counts == null ? 0 : count(counts.get("low"))) + legacyLow,
        (counts == null ? 0 : count(counts.get("out"))) + legacyOut,
        counts == null ? 0 : count(counts.get("today")),
        counts == null ? 0 : count(counts.get("week")),
        counts == null ? 0 : count(counts.get("month")),
        lowest);
  }

  /** Products of the given lots, by id, in one read (none when no lot has a product). */
  private Map<String, Product> products(List<Document> a, List<Document> b) {
    Set<String> ids = new LinkedHashSet<>();
    for (List<Document> rows : List.of(a, b)) {
      for (Document d : rows) {
        String pid = productId(d);
        if (pid != null) {
          ids.add(pid);
        }
      }
    }
    Map<String, Product> out = new HashMap<>();
    if (!ids.isEmpty()) {
      for (Product p : productRepository.findAllById(ids)) {
        out.put(p.getId(), p);
      }
    }
    return out;
  }

  /** {@code currentCount × factor} rounded half-up, factor from the product (1 when absent/≤0); 0 without a count. */
  static int legacyBaseCount(Object currentCount, Product product) {
    if (currentCount == null) {
      return 0;
    }
    int factor = 1;
    if (product != null
        && product.getUnitConversions() != null
        && product.getUnitConversions().getFactor() != null
        && product.getUnitConversions().getFactor() > 0) {
      factor = product.getUnitConversions().getFactor();
    }
    return decimal(currentCount).multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.HALF_UP).intValue();
  }

  private static String id(Document d) {
    return String.valueOf(d.get("_id"));
  }

  private static String productId(Document d) {
    Object v = d.get("productId");
    return v instanceof String s && StringUtils.hasText(s) ? s : null;
  }

  private static Document nonBlank(String field) {
    return new Document("$gt", List.of(new Document("$strLenCP", new Document("$trim", new Document("input", field))), 0));
  }

  private static Document gte(String field, Instant from) {
    return new Document("$gte", List.of(field, Date.from(from)));
  }

  private static Document sumIf(Document condition) {
    return new Document("$sum", new Document("$cond", List.of(condition, 1, 0)));
  }

  private Document aggregateOne(Class<?> entity, List<Document> pipeline) {
    Document out =
        mongoTemplate
            .getCollection(mongoTemplate.getCollectionName(entity))
            .aggregate(pipeline)
            .maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS)
            .first();
    return out == null ? new Document() : out;
  }

  private static Document first(Document facet, String key) {
    List<Document> rows = facet.getList(key, Document.class, List.of());
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static long countOf(Document facet, String key) {
    Document row = first(facet, key);
    return row == null ? 0 : count(row.get("n"));
  }

  static BigDecimal decimal(Object v) {
    if (v instanceof Decimal128 d) {
      return d.bigDecimalValue();
    }
    if (v instanceof BigDecimal b) {
      return b;
    }
    if (v instanceof Integer || v instanceof Long) {
      return BigDecimal.valueOf(((Number) v).longValue());
    }
    if (v instanceof Number n) {
      return BigDecimal.valueOf(n.doubleValue());
    }
    return BigDecimal.ZERO;
  }

  static long count(Object v) {
    if (v instanceof Decimal128 d) {
      return d.bigDecimalValue().longValue();
    }
    return v instanceof Number n ? n.longValue() : 0;
  }
}
