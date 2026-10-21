package com.inventory.analytics.utils;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.InventoryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The analytics tabs' reads of completed sales, done inside MongoDB so the API never holds the
 * shop's bills in memory.
 *
 * <p>Item-level numbers (quantities, revenue, cost, discounts) follow rules with rounding and
 * fallbacks that live in Java. Rather than re-implement them in the database, MongoDB groups
 * <em>identical sale lines</em> — same lot reference, name, quantity, price, discount, MRP (and IST
 * day/hour when a chart needs it) — and counts them. Java then applies the unchanged per-line rules
 * once per group and multiplies by the count, which gives exactly the old sums. A month of a 10k-sale
 * shop is ~240 groups instead of ~11k lines.
 *
 * <p>Bill-level numbers (grand/tax/discount totals, counts) are summed by MongoDB directly. Every
 * read is "completed, sold at or after {@code start} and at or before {@code end}" (both inclusive,
 * as before), served by the {@code {shopId, status, soldAt}} index.
 */
@Component
public class AnalyticsSalesQueries {

  /** A runaway aggregation must not hold a pool connection for minutes. */
  static final long MAX_TIME_MS = 30_000;

  /** Item fields that decide the per-line numbers; all are part of a group's key. */
  private static final String[] LINE_FIELDS = {
    "stockRef", "sellableRef", "sellMode", "inventoryId", "menuItemId", "name", "quantity",
    "baseQuantity", "unitFactor", "priceToRetail", "sellingPrice", "discount", "maximumRetailPrice"
  };

  private final MongoTemplate mongoTemplate;
  private final InventoryRepository inventoryRepository;

  @Autowired
  public AnalyticsSalesQueries(MongoTemplate mongoTemplate, InventoryRepository inventoryRepository) {
    this.mongoTemplate = mongoTemplate;
    this.inventoryRepository = inventoryRepository;
  }

  /**
   * {@code lines} identical sale lines (same {@link #line} fields). {@code bucket} is the IST day
   * (or hour) when asked for, {@code lastSoldAt} the latest bill they appear on, {@code billIds} the
   * distinct bills they appear on (only when asked for).
   */
  public record LineGroup(PurchaseItem line, long lines, String bucket, Instant lastSoldAt, Set<String> billIds) {

    /** The stock lot the line sold from (same derivation as {@link PurchaseItem#getInventoryId()}). */
    public String lotId() {
      return line.getInventoryId();
    }

    /** Base units of one line: base quantity, else quantity × unit factor half-up, else 0. */
    public int baseQuantity() {
      if (line.getBaseQuantity() != null) {
        return line.getBaseQuantity();
      }
      if (line.getQuantity() == null) {
        return 0;
      }
      int factor = line.getUnitFactor() != null && line.getUnitFactor() > 0 ? line.getUnitFactor() : 1;
      return line.getQuantity().multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** Quantity the price applies to: quantity, else base quantity ÷ factor (4 dp, half-up), else 0. */
    public BigDecimal pricingQuantity() {
      if (line.getQuantity() != null) {
        return line.getQuantity();
      }
      if (line.getBaseQuantity() != null) {
        int factor = line.getUnitFactor() != null && line.getUnitFactor() > 0 ? line.getUnitFactor() : 1;
        return BigDecimal.valueOf(line.getBaseQuantity()).divide(BigDecimal.valueOf(factor), 4, RoundingMode.HALF_UP);
      }
      return BigDecimal.ZERO;
    }

    /** {@code value} added up over the group's lines. */
    public BigDecimal times(BigDecimal value) {
      return value.multiply(BigDecimal.valueOf(lines));
    }

    /** {@code value} added up over the group's lines. */
    public int times(int value) {
      return Math.toIntExact(value * lines);
    }
  }

  /** Bill-level sums; null totals are skipped, as before. */
  public record BillTotals(long bills, BigDecimal grandTotal, BigDecimal taxTotal, BigDecimal discountTotal) {}

  /** Bills of one IST day (or hour): count and sum of grand totals (null as 0). */
  public record BucketTotal(String bucket, long bills, BigDecimal grandTotal) {}

  /**
   * A customer's bills over all time. {@code customerId} is set for bills with one; otherwise
   * {@code billName} is the name typed on the bill and is the key. {@code billName} for an id is the
   * earliest name typed on one of their bills (used when the customer record is gone).
   */
  public record CustomerTotal(
      String customerId,
      String billName,
      long bills,
      BigDecimal grandTotal,
      Instant firstSoldAt,
      Instant lastSoldAt,
      long billsInPeriod) {}

  /** Identical sale lines of the period, counted. {@code buckets}: none, IST "day" or "hour". */
  public List<LineGroup> lineGroups(String shopId, Instant start, Instant end, String buckets, boolean withBillIds) {
    Document key = new Document();
    for (String f : LINE_FIELDS) {
      key.append(f, "$items." + f);
    }
    if (buckets != null) {
      key.append("bucket", bucket(buckets));
    }
    Document group =
        new Document("_id", key)
            .append("lines", new Document("$sum", 1))
            .append("lastSoldAt", new Document("$max", "$soldAt"));
    if (withBillIds) {
      group.append("billIds", new Document("$addToSet", "$_id"));
    }
    List<Document> pipeline =
        List.of(
            new Document("$match", periodMatch(shopId, start, end)),
            new Document("$project", new Document("items", 1).append("soldAt", 1)),
            new Document("$unwind", "$items"),
            new Document("$group", group));
    List<LineGroup> out = new ArrayList<>();
    for (Document row : aggregate(pipeline)) {
      Document k = row.get("_id", Document.class);
      Set<String> billIds = null;
      if (withBillIds) {
        billIds = new HashSet<>();
        for (Object id : row.getList("billIds", Object.class, List.of())) {
          billIds.add(String.valueOf(id));
        }
      }
      out.add(new LineGroup(line(k), count(row.get("lines")), k.getString("bucket"), instant(row.get("lastSoldAt")), billIds));
    }
    return out;
  }

  /** Count and sums of the period's bills. */
  public BillTotals billTotals(String shopId, Instant start, Instant end) {
    List<Document> pipeline =
        List.of(
            new Document("$match", periodMatch(shopId, start, end)),
            new Document(
                "$group",
                new Document("_id", null)
                    .append("bills", new Document("$sum", 1))
                    .append("grandTotal", new Document("$sum", "$grandTotal"))
                    .append("taxTotal", new Document("$sum", "$taxTotal"))
                    .append("discountTotal", new Document("$sum", "$discountTotal"))));
    List<Document> rows = aggregate(pipeline);
    if (rows.isEmpty()) {
      return new BillTotals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
    Document r = rows.get(0);
    return new BillTotals(count(r.get("bills")), decimal(r.get("grandTotal")), decimal(r.get("taxTotal")), decimal(r.get("discountTotal")));
  }

  /** The period's bills per IST day (or hour). */
  public List<BucketTotal> bucketTotals(String shopId, Instant start, Instant end, String buckets) {
    List<Document> pipeline =
        List.of(
            new Document("$match", periodMatch(shopId, start, end)),
            new Document(
                "$group",
                new Document("_id", bucket(buckets))
                    .append("bills", new Document("$sum", 1))
                    .append("grandTotal", new Document("$sum", "$grandTotal"))));
    List<BucketTotal> out = new ArrayList<>();
    for (Document r : aggregate(pipeline)) {
      out.add(new BucketTotal(r.getString("_id"), count(r.get("bills")), decimal(r.get("grandTotal"))));
    }
    return out;
  }

  /**
   * Every customer's bills over all time (bills with neither a customer id nor a typed name are
   * skipped), with how many fall in {@code [start, end]}.
   */
  public List<CustomerTotal> customerTotals(String shopId, Instant start, Instant end) {
    Document hasId = new Document("$ne", Arrays.asList(new Document("$type", "$customerId"), "missing"));
    Document idNotNull = new Document("$and", Arrays.asList(hasId, new Document("$ne", Arrays.asList("$customerId", null))));
    Document nameNotNull = new Document("$ne", Arrays.asList(new Document("$ifNull", Arrays.asList("$customerName", null)), null));
    Document inPeriod =
        new Document(
            "$and",
            Arrays.asList(
                new Document("$gte", Arrays.asList("$soldAt", Date.from(start))),
                new Document("$lte", Arrays.asList("$soldAt", Date.from(end)))));
    List<Document> pipeline =
        Arrays.asList(
            new Document("$match", allTimeMatch(shopId)),
            new Document(
                "$project",
                new Document("soldAt", 1)
                    .append("grandTotal", 1)
                    .append("customerName", 1)
                    .append("customerId", new Document("$cond", Arrays.asList(idNotNull, "$customerId", null)))
                    .append("idKey", idNotNull)),
            new Document("$match", new Document("$expr", new Document("$or", Arrays.asList("$idKey", nameNotNull)))),
            new Document(
                "$group",
                new Document(
                        "_id",
                        new Document("id", "$customerId")
                            .append("name", new Document("$cond", Arrays.asList("$idKey", null, "$customerName"))))
                    .append("bills", new Document("$sum", 1))
                    .append("grandTotal", new Document("$sum", "$grandTotal"))
                    .append("first", new Document("$min", "$soldAt"))
                    .append("last", new Document("$max", "$soldAt"))
                    .append("inPeriod", new Document("$sum", new Document("$cond", Arrays.asList(inPeriod, 1, 0))))
                    // earliest typed name: documents compare field by field, so this is min by soldAt
                    .append(
                        "earliestName",
                        new Document(
                            "$min",
                            new Document(
                                "$cond",
                                Arrays.asList(
                                    nameNotNull,
                                    new Document("t", "$soldAt").append("n", "$customerName"),
                                    null))))));
    List<CustomerTotal> out = new ArrayList<>();
    for (Document r : aggregate(pipeline)) {
      Document k = r.get("_id", Document.class);
      String id = k.getString("id");
      Document earliest = r.get("earliestName", Document.class);
      String name = id == null ? k.getString("name") : (earliest == null ? null : earliest.getString("n"));
      out.add(
          new CustomerTotal(
              id,
              name,
              count(r.get("bills")),
              decimal(r.get("grandTotal")),
              instant(r.get("first")),
              instant(r.get("last")),
              count(r.get("inPeriod"))));
    }
    return out;
  }

  /** Number of completed bills ever (with a sale time). */
  public long allTimeBills(String shopId) {
    return mongoTemplate
        .getCollection(mongoTemplate.getCollectionName(Purchase.class))
        .countDocuments(allTimeMatch(shopId), new com.mongodb.client.model.CountOptions().maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS));
  }

  /** Latest sale time per lot over all completed bills. */
  public Map<String, Instant> lastSoldByLot(String shopId) {
    Document key = new Document();
    for (String f : new String[] {"stockRef", "sellableRef", "sellMode", "inventoryId", "menuItemId"}) {
      key.append(f, "$items." + f);
    }
    List<Document> pipeline =
        List.of(
            new Document("$match", allTimeMatch(shopId)),
            new Document("$project", new Document("items", 1).append("soldAt", 1)),
            new Document("$unwind", "$items"),
            new Document("$group", new Document("_id", key).append("last", new Document("$max", "$soldAt"))));
    Map<String, Instant> out = new HashMap<>();
    for (Document r : aggregate(pipeline)) {
      String lot = line(r.get("_id", Document.class)).getInventoryId();
      Instant last = instant(r.get("last"));
      if (lot != null && last != null) {
        out.merge(lot, last, (a, b) -> a.isAfter(b) ? a : b);
      }
    }
    return out;
  }

  /** The lots the groups sold from, read once (product and pricing filled in by the read aspects). */
  public Map<String, Inventory> lotsOf(Collection<LineGroup> groups) {
    Set<String> ids = new LinkedHashSet<>();
    for (LineGroup g : groups) {
      String lot = g.lotId();
      if (StringUtils.hasText(lot)) {
        ids.add(lot);
      }
    }
    return lots(ids);
  }

  /** Lots by id, read once (product and pricing filled in by the read aspects). */
  public Map<String, Inventory> lots(Collection<String> ids) {
    Map<String, Inventory> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    for (Inventory inv : inventoryRepository.findAllById(ids)) {
      out.put(inv.getId(), inv);
    }
    return out;
  }

  // --- helpers ---

  private static Document periodMatch(String shopId, Instant start, Instant end) {
    return new Document("shopId", shopId)
        .append("status", PurchaseStatus.COMPLETED.name())
        .append("soldAt", new Document("$gte", Date.from(start)).append("$lte", Date.from(end)));
  }

  private static Document allTimeMatch(String shopId) {
    return new Document("shopId", shopId)
        .append("status", PurchaseStatus.COMPLETED.name())
        .append("soldAt", new Document("$ne", null));
  }

  /**
   * The IST bucket of a bill's {@code soldAt} as a string {@link AnalyticsPeriods#keyOf} understands:
   * the hour for hourly charts, the Monday of the week / the 1st of the month for weekly / monthly
   * charts (so MongoDB returns one group per week or month, not per day), else the day.
   */
  private static Document bucket(String buckets) {
    String zone = AnalyticsPeriods.ZONE.getId();
    Object date = "$soldAt";
    String format = AnalyticsPeriods.MONGO_DAY_FORMAT;
    String g = buckets == null ? "day" : buckets.toLowerCase();
    if (g.equals("hour")) {
      format = AnalyticsPeriods.MONGO_HOUR_FORMAT;
    } else if (g.equals("week")) {
      date = new Document("$dateTrunc", new Document("date", "$soldAt").append("unit", "week")
          .append("timezone", zone).append("startOfWeek", "monday"));
    } else if (g.equals("month")) {
      date = new Document("$dateTrunc", new Document("date", "$soldAt").append("unit", "month").append("timezone", zone));
    }
    return new Document("$dateToString", new Document("format", format).append("date", date).append("timezone", zone));
  }

  private List<Document> aggregate(List<Document> pipeline) {
    return mongoTemplate
        .getCollection(mongoTemplate.getCollectionName(Purchase.class))
        .aggregate(pipeline)
        .maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS)
        .into(new ArrayList<>());
  }

  /**
   * A sale line as the entity mapping would read it: numbers converted the way Spring converts them,
   * and a missing {@code priceToRetail} taken from the legacy {@code sellingPrice} the way
   * {@code PurchaseBackwardCompatCallback} does.
   */
  static PurchaseItem line(Document k) {
    PurchaseItem item = new PurchaseItem();
    item.setStockRef(k.getString("stockRef"));
    item.setSellableRef(k.getString("sellableRef"));
    item.setSellMode(k.getString("sellMode"));
    item.setMongoInventoryId(k.getString("inventoryId"));
    item.setMongoMenuItemId(k.getString("menuItemId"));
    item.setName(k.getString("name"));
    item.setQuantity(asBigDecimal(k.get("quantity")));
    item.setBaseQuantity(asInteger(k.get("baseQuantity")));
    item.setUnitFactor(asInteger(k.get("unitFactor")));
    item.setDiscount(asBigDecimal(k.get("discount")));
    item.setMaximumRetailPrice(asBigDecimal(k.get("maximumRetailPrice")));
    BigDecimal price = asBigDecimal(k.get("priceToRetail"));
    if (price == null) {
      Object selling = k.get("sellingPrice");
      if (selling instanceof Number n) {
        price = BigDecimal.valueOf(n.doubleValue()); // as the callback does
      } else if (selling != null) {
        try {
          price = new BigDecimal(selling.toString());
        } catch (NumberFormatException e) {
          price = null;
        }
      }
    }
    item.setPriceToRetail(price);
    return item;
  }

  static BigDecimal asBigDecimal(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Decimal128 d) {
      return d.bigDecimalValue();
    }
    if (v instanceof BigDecimal b) {
      return b;
    }
    // Spring converts other numbers through their string form
    return new BigDecimal(v.toString());
  }

  static Integer asInteger(Object v) {
    return v instanceof Number n ? n.intValue() : null;
  }

  static BigDecimal decimal(Object v) {
    BigDecimal b = asBigDecimal(v);
    return b == null ? BigDecimal.ZERO : b;
  }

  static long count(Object v) {
    return v instanceof Number n ? n.longValue() : 0;
  }

  private static Instant instant(Object v) {
    return v instanceof Date d ? d.toInstant() : null;
  }
}
