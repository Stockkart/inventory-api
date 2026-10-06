package com.inventory.product.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.labels.LabelFieldCatalogService;
import com.inventory.product.labels.LabelFieldKeys;
import com.inventory.product.labels.VerticalValueTypeMapper;
import com.inventory.product.rest.dto.request.SearchRequest;
import com.inventory.product.rest.dto.request.SearchRequest.FilterGroup;
import com.inventory.product.search.SearchFieldCatalogService.ShopSearchContext;
import com.inventory.product.service.vertical.SchemaLoader;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Timing of the raw pipeline on a large shop (advanced-product-search R10.7): 50,000 lots across
 * 10,000 products, pharmacy schema. Prints the per-search time and asserts the p95 of the ten
 * representative searches is under the budget. Runs only with {@code -Dstockkart.test.mongo.uri}.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class InventorySearchTimingIT {

  private static final String SHOP = "timing-shop";
  private static final int PRODUCTS = Integer.getInteger("stockkart.test.timing.products", 10_000);
  private static final int LOTS_PER_PRODUCT = Integer.getInteger("stockkart.test.timing.lotsPerProduct", 5);
  private static final long BUDGET_MS = Long.getLong("stockkart.test.timing.budgetMs", 300);
  private static final String[] COMPANIES = {"Cipla", "GSK", "Sun Pharma", "Zydus", "Lupin", "Dr Reddy", "Alkem", "Mankind"};
  private static final String[] LOCATIONS = {"J1", "J2", "J3", "J4", "K1", "K2"};
  private static final String[] STEMS = {"Paracetamol", "Amoxicillin", "Azithromycin", "Cetirizine", "Metformin", "Atorvastatin", "Pantoprazole", "Ibuprofen"};

  private static MongoClient client;
  private static MongoTemplate template;
  private static ShopSearchContext ctx;
  private static InventorySearchPlanner planner;
  private static DeferredSortRunner sorter;

  @BeforeAll
  static void seed() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0] + "_timing";
    template = new MongoTemplate(client, db);
    template.getDb().drop();

    Shop shop = new Shop();
    shop.setShopId(SHOP);
    shop.setShopType(ShopType.RETAILER);
    shop.setVerticalId("medical");
    shop.setPluginVersion("1.0.0");
    ShopRepository shops = mock(ShopRepository.class);
    when(shops.findById(SHOP)).thenReturn(Optional.of(shop));
    PricingRepository pricing = mock(PricingRepository.class);
    when(pricing.findDistinctRateNamesByShopId(anyString())).thenReturn(List.of());
    SchemaLoader loader = mock(SchemaLoader.class);
    VerticalSchema schema =
        SearchTestFixtures.schema(
            List.of(
                SearchTestFixtures.field("batchNo", null, "Batch Number", "string", "extension", true, false, null),
                SearchTestFixtures.field("expiryDate", null, "Expiry Date", "date", "extension", true, true, null)),
            "expiryDate", "asc");
    when(loader.load(anyString(), any())).thenReturn(schema);
    ctx = new SearchFieldCatalogService(shops, new LabelFieldCatalogService(shops, pricing, loader, new VerticalValueTypeMapper()), loader).context(shop);
    planner = new InventorySearchPlanner(new MongoPreQueryRunner(template));
    sorter = new DeferredSortRunner(template);

    Instant now = Instant.now();
    List<Document> products = new ArrayList<>(2_000);
    List<Document> lots = new ArrayList<>(10_000);
    List<Document> ext = new ArrayList<>(10_000);
    int seq = 0;
    for (int p = 0; p < PRODUCTS; p++) {
      ObjectId pid = new ObjectId();
      String name = STEMS[p % STEMS.length] + " " + (100 + (p % 40) * 25) + "mg";
      products.add(new Document("_id", pid).append("shopId", SHOP).append("name", name).append("normalizedName", name.toLowerCase())
          .append("companyName", COMPANIES[p % COMPANIES.length]).append("barcode", "BC" + String.format("%06d", p)).append("hsn", "3004" + (p % 5)));
      for (int l = 0; l < LOTS_PER_PRODUCT; l++, seq++) {
        ObjectId lid = new ObjectId();
        lots.add(new Document("_id", lid).append("shopId", SHOP).append("productId", pid.toHexString()).append("location", LOCATIONS[seq % LOCATIONS.length])
            .append("currentCount", new Decimal128(BigDecimal.valueOf(seq % 11 == 0 ? 0 : 5 + seq % 60))).append("thresholdCount", 8)
            .append("billingMode", seq % 6 == 0 ? "BASIC" : null).append("createdAt", Date.from(now.minus(seq % 5000, ChronoUnit.MINUTES))));
        if (seq % 13 != 0) {
          ext.add(new Document("shopId", SHOP).append("inventoryId", lid.toHexString()).append("verticalId", "medical")
              .append("batchNo", "B" + String.format("%06d", seq)).append("expiryDate", Date.from(now.plus(seq % 700, ChronoUnit.DAYS))));
        }
      }
      if (products.size() >= 2_000) { template.getCollection("product").insertMany(products); products.clear(); }
      if (lots.size() >= 10_000) { template.getCollection("inventory").insertMany(lots); lots.clear(); }
      if (ext.size() >= 10_000) { template.getCollection("inventory_ext_medical").insertMany(ext); ext.clear(); }
    }
    if (!products.isEmpty()) template.getCollection("product").insertMany(products);
    if (!lots.isEmpty()) template.getCollection("inventory").insertMany(lots);
    if (!ext.isEmpty()) template.getCollection("inventory_ext_medical").insertMany(ext);

    // the indexes SearchIndexMigration creates, plus the extension ones the plugins declare
    template.getCollection("product").createIndex(new Document("shopId", 1).append("companyName", 1));
    template.getCollection("product").createIndex(new Document("shopId", 1).append("normalizedName", 1).append("companyName", 1).append("baseUnit", 1));
    template.getCollection("product").createIndex(new Document("shopId", 1).append("hsn", 1));
    template.getCollection("inventory").createIndex(new Document("shopId", 1).append("productId", 1));
    template.getCollection("inventory").createIndex(new Document("shopId", 1).append("location", 1));
    template.getCollection("inventory").createIndex(new Document("shopId", 1).append("currentCount", 1));
    template.getCollection("inventory").createIndex(new Document("shopId", 1).append("batchNo", 1), new com.mongodb.client.model.IndexOptions().sparse(true));
    template.getCollection("inventory").createIndex(new Document("shopId", 1).append("createdAt", -1));
    template.getCollection("inventory_ext_medical").createIndex(new Document("inventoryId", 1), new com.mongodb.client.model.IndexOptions().unique(true));
    template.getCollection("inventory_ext_medical").createIndex(new Document("shopId", 1).append("inventoryId", 1));
    template.getCollection("inventory_ext_medical").createIndex(new Document("shopId", 1).append("batchNo", 1));
    template.getCollection("inventory_ext_medical").createIndex(new Document("shopId", 1).append("expiryDate", 1));
  }

  @AfterAll
  static void tearDown() {
    // -Dstockkart.test.timing.keep=true leaves the data behind for profiling with mongosh
    if (template != null && !Boolean.getBoolean("stockkart.test.timing.keep")) template.getDb().drop();
    if (client != null) client.close();
  }

  @Test
  void tenRepresentativeSearchesStayWithinBudget() {
    List<SearchRequest> searches = Arrays.asList(
        req(null, List.of(), List.of(), null),                                                                        // 1 full list, default sort
        req("para*mol", List.of(), List.of(), null),                                                                  // 2 text only
        req("para*mol", List.of(), List.of(LabelFieldKeys.COMPANY_NAME, LabelFieldKeys.LOCATION), null),             // 3 text + 2 facets
        req(null, List.of(in(LabelFieldKeys.COMPANY_NAME, "Cipla", "GSK")), List.of(LabelFieldKeys.COMPANY_NAME), null), // 4 company filter + own facet
        req("para*mol", List.of(in(LabelFieldKeys.COMPANY_NAME, "Cipla"), in(LabelFieldKeys.LOCATION, "J2")),
            List.of(LabelFieldKeys.COMPANY_NAME, LabelFieldKeys.LOCATION, LabelFieldKeys.STOCK_STATE, LabelFieldKeys.BILLING_MODE), null), // 5 the full panel
        req(null, List.of(new FilterGroup(LabelFieldKeys.EXPIRY_DATE, "withinDays", List.of("90"), null, null)), List.of(), "expiryDate:asc"), // 6 expiry window
        req(null, List.of(in(LabelFieldKeys.STOCK_STATE, "LOW_STOCK")), List.of(LabelFieldKeys.STOCK_STATE), null),   // 7 computed stock
        req("B00012", List.of(), List.of(), null),                                                                    // 8 batch prefix (extension text)
        req("cipla", List.of(), List.of(LabelFieldKeys.COMPANY_NAME), "productName:asc"),                             // 9 company text + sort by name
        req(null, List.of(in(LabelFieldKeys.LOCATION, "J1", "J2"), in(LabelFieldKeys.BILLING_MODE, "BASIC")), List.of(LabelFieldKeys.LOCATION), "receivedDate:desc")); // 10 lot-only

    SearchRequestValidator validator = new SearchRequestValidator();
    List<Long> times = new ArrayList<>();
    for (int i = 0; i < searches.size(); i++) {
      ValidatedSearch v = validator.validate(searches.get(i), ctx);
      // warm once, then measure
      runOnce(v);
      long t0 = System.nanoTime();
      Document out = runOnce(v);
      long ms = (System.nanoTime() - t0) / 1_000_000;
      times.add(ms);
      long total = out.getList("total", Document.class).isEmpty() ? 0 : out.getList("total", Document.class).get(0).getInteger("n");
      System.out.printf("[timing] #%d %5d ms  total=%d%s%n", i + 1, ms, total, out.containsKey("_sortMs") ? "  (of which sort " + out.get("_sortMs") + " ms)" : "");
    }
    List<Long> sorted = times.stream().sorted().toList();
    long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    System.out.printf("[timing] p95=%d ms budget=%d ms (products=%d lots=%d)%n", p95, BUDGET_MS, PRODUCTS, PRODUCTS * LOTS_PER_PRODUCT);
    assertThat(p95).isLessThanOrEqualTo(BUDGET_MS);
  }

  /** Pipeline plus, when the sort key lives on another collection, the in-memory sort — as the engine does. */
  @SuppressWarnings("unchecked")
  private static Document runOnce(ValidatedSearch v) {
    SearchPlan plan = planner.plan(v, ctx);
    if (plan.shortCircuit()) return new Document("total", List.of());
    Document d = template.getCollection("inventory").aggregate(plan.stages()).maxTime(10, TimeUnit.SECONDS).first();
    if (d == null) return new Document("total", List.of());
    if (plan.sortsInMemory()) {
      long t0 = System.nanoTime();
      List<Document> candidates = (List<Document>) d.getOrDefault(SearchPlan.RESULTS, List.of());
      sorter.sortAndPage(candidates, plan.deferredSort(), v.skip(), v.size(), 10_000);
      d.put("_sortMs", (System.nanoTime() - t0) / 1_000_000);
    }
    return d;
  }

  private static SearchRequest req(String text, List<FilterGroup> filters, List<String> facets, String sort) {
    return new SearchRequest(text, null, filters, null, facets, sort, 0, 20, false, "product-search");
  }

  private static FilterGroup in(String field, String... values) {
    return new FilterGroup(field, "in", List.of(values), null, null);
  }
}
