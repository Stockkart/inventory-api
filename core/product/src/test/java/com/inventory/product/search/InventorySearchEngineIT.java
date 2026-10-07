package com.inventory.product.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.labels.LabelFieldCatalogService;
import com.inventory.product.labels.LabelFieldKeys;
import com.inventory.product.labels.VerticalValueTypeMapper;
import com.inventory.product.rest.dto.request.SearchRequest;
import com.inventory.product.rest.dto.request.SearchRequest.FilterGroup;
import com.inventory.product.rest.dto.response.InventorySummaryDto;
import com.inventory.product.rest.dto.response.SearchResponse;
import com.inventory.product.rest.dto.response.SearchResponse.FacetValue;
import com.inventory.product.search.SearchFieldCatalogService.ShopSearchContext;
import com.inventory.product.service.vertical.SchemaLoader;
import com.inventory.product.utils.InventoryFreeTextSearch;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The engine against a real MongoDB (advanced-product-search R4.2–R4.5, R5.1–R5.3, R2.3, R10.3).
 *
 * <p>Runs only when {@code -Dstockkart.test.mongo.uri=…} is set, so the normal test run needs no
 * database. To run it locally against a throwaway Mongo:
 *
 * <pre>
 *   docker run -d --rm --name stockkart-test-mongo -p 27018:27017 --tmpfs /data/db mongo:7
 *   mvn -pl core/product -am test -Dtest='InventorySearch*IT' \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27018/stockkart_test
 *   docker stop stockkart-test-mongo
 * </pre>
 * Seeds one pharmacy-like shop: 3 companies × 4 locations, 2,400 lots, batch/expiry in the extension
 * collection, some sold-out lots, some lots with no extension row and no expiry.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class InventorySearchEngineIT {

  private static final String SHOP = "it-shop";
  private static final String[] COMPANIES = {"Cipla", "GSK", "Sun Pharma"};
  private static final String[] LOCATIONS = {"J1", "J2", "J3", "J4"};
  private static final int PRODUCTS_PER_COMPANY = 20; // 60 products
  private static final int LOTS_PER_PRODUCT = 40; // 2,400 lots

  private static MongoClient client;
  private static MongoTemplate template;
  private static InventorySearchEngine engine;
  private static InventoryRepository inventoryRepository;
  private static SearchFieldCatalogService catalogService;

  @BeforeAll
  static void seed() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0];
    template = new MongoTemplate(client, db);
    template.getDb().drop();

    // --- shop + schema (pharmacy: batchNo + expiryDate in extension) ---
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
                SearchTestFixtures.field("name", null, "Name", "string", "core", false, false, null),
                SearchTestFixtures.field("batchNo", null, "Batch Number", "string", "extension", true, false, null),
                SearchTestFixtures.field("expiryDate", null, "Expiry Date", "date", "extension", true, true, null),
                SearchTestFixtures.field("companyName", null, "Company", "string", "core", false, false, null),
                SearchTestFixtures.field("schedule", null, "Schedule", "enum", "extension", true, false, List.of("H", "H1", "OTC"))),
            "expiryDate",
            "asc");
    when(loader.load(anyString(), any())).thenReturn(schema);
    LabelFieldCatalogService labelCatalog =
        new LabelFieldCatalogService(shops, pricing, loader, new VerticalValueTypeMapper());
    catalogService = new SearchFieldCatalogService(shops, labelCatalog, loader);

    // --- data ---
    List<Document> products = new ArrayList<>();
    List<Document> lots = new ArrayList<>();
    List<Document> ext = new ArrayList<>();
    Instant now = Instant.now();
    int lotSeq = 0;
    for (int c = 0; c < COMPANIES.length; c++) {
      for (int p = 0; p < PRODUCTS_PER_COMPANY; p++) {
        ObjectId pid = new ObjectId();
        String name = (p % 2 == 0 ? "Paracetamol " : "Amoxicillin ") + (250 + p * 25) + "mg";
        products.add(
            new Document("_id", pid)
                .append("shopId", SHOP)
                .append("name", name)
                .append("normalizedName", name.toLowerCase())
                .append("companyName", COMPANIES[c])
                .append("barcode", "BC" + c + String.format("%03d", p))
                .append("hsn", "3004" + (p % 3)));
        for (int l = 0; l < LOTS_PER_PRODUCT; l++) {
          ObjectId lid = new ObjectId();
          int seq = lotSeq++;
          int stock = seq % 10 == 0 ? 0 : (seq % 7 == 0 ? 2 : 10 + seq % 50);
          lots.add(
              new Document("_id", lid)
                  .append("shopId", SHOP)
                  .append("productId", pid.toHexString())
                  .append("location", LOCATIONS[seq % LOCATIONS.length])
                  .append("currentCount", new org.bson.types.Decimal128(BigDecimal.valueOf(stock)))
                  .append("thresholdCount", 5)
                  .append("billingMode", seq % 5 == 0 ? "BASIC" : null)
                  .append("createdAt", Date.from(now.minus(seq, ChronoUnit.HOURS))));
          // every 9th lot has no extension row (no batch, no expiry)
          if (seq % 9 != 0) {
            ext.add(
                new Document("shopId", SHOP)
                    .append("inventoryId", lid.toHexString())
                    .append("verticalId", "medical")
                    .append("batchNo", "B" + String.format("%05d", seq))
                    .append("expiryDate", Date.from(now.plus(1 + seq % 400, ChronoUnit.DAYS))) // never "today", so "within N days" is unambiguous
                    .append("schedule", seq % 3 == 0 ? "H" : seq % 3 == 1 ? "H1" : "OTC"));
          }
        }
      }
    }
    template.getCollection("product").insertMany(products);
    template.getCollection("inventory").insertMany(lots);
    template.getCollection("inventory_ext_medical").insertMany(ext);
    template.getCollection("product").createIndex(new Document("shopId", 1).append("companyName", 1));
    template.getCollection("inventory").createIndex(new Document("shopId", 1).append("productId", 1));
    template.getCollection("inventory").createIndex(new Document("shopId", 1).append("location", 1));
    // what the medical plugin declares and SearchIndexMigration creates
    template.getCollection("inventory_ext_medical").createIndex(new Document("inventoryId", 1), new com.mongodb.client.model.IndexOptions().unique(true));
    template.getCollection("inventory_ext_medical").createIndex(new Document("shopId", 1).append("expiryDate", 1));
    template.getCollection("inventory_ext_medical").createIndex(new Document("shopId", 1).append("batchNo", 1));

    inventoryRepository = mock(InventoryRepository.class);
    when(inventoryRepository.findByIdIn(any()))
        .thenAnswer(
            inv -> {
              List<String> ids = inv.getArgument(0);
              List<Inventory> out = new ArrayList<>();
              for (Document d : template.getCollection("inventory").find(new Document("_id", new Document("$in", ids.stream().map(ObjectId::new).toList())))) {
                Inventory i = new Inventory();
                i.setId(d.getObjectId("_id").toHexString());
                i.setShopId(d.getString("shopId"));
                i.setLocation(d.getString("location"));
                i.setProductId(d.getString("productId"));
                out.add(i);
              }
              return out;
            });

    engine =
        new InventorySearchEngine(
            catalogService,
            new SearchRequestValidator(),
            new InventorySearchPlanner(new MongoPreQueryRunner(template)),
            new DeferredSortRunner(template),
            template,
            inventoryRepository,
            new SearchMetrics(new MetricsWrapper(new SimpleMeterRegistry())));
  }

  @AfterAll
  static void tearDown() {
    if (template != null) template.getDb().drop();
    if (client != null) client.close();
  }

  // ---- helpers ------------------------------------------------------------------------------------

  private static SearchResponse run(SearchRequest r) {
    return engine.search(SHOP, r, (shop, lots) -> lots.stream().map(InventorySearchEngineIT::summary).toList());
  }

  private static InventorySummaryDto summary(Inventory i) {
    InventorySummaryDto s = new InventorySummaryDto();
    s.setId(i.getId());
    s.setLocation(i.getLocation());
    return s;
  }

  private static SearchRequest req(String text, List<FilterGroup> filters, String match, List<String> facets, String sort, int page, int size, Boolean zero) {
    return new SearchRequest(text, null, filters, match, facets, sort, page, size, zero, "product-search");
  }

  private static FilterGroup in(String field, String... values) {
    return new FilterGroup(field, "in", List.of(values), null, null);
  }

  private static long count(Document filter) {
    return template.getCollection("inventory").countDocuments(filter);
  }

  private static Map<String, Long> facetMap(SearchResponse r, String key) {
    return r.facets().get(key).stream().collect(Collectors.toMap(FacetValue::value, FacetValue::count));
  }

  // ---- tests --------------------------------------------------------------------------------------

  @Test
  void emptyRequestReturnsWholeShopWithExactTotal() {
    SearchResponse r = run(req(null, List.of(), null, List.of(), null, 0, 20, true));
    assertThat(r.page().getTotalItems()).isEqualTo(count(new Document("shopId", SHOP)));
    assertThat(r.page().getTotalPages()).isEqualTo((int) Math.ceil(2400 / 20.0));
    assertThat(r.data()).hasSize(20);
    assertThat(r.appliedSort()).isEqualTo("expiryDate:asc");
  }

  @Test
  void zeroStockIsExcludedBeforeCounting() {
    SearchResponse r = run(req(null, List.of(), null, List.of(), null, 0, 20, false));
    long expected = count(new Document("shopId", SHOP).append("currentCount", new Document("$gt", 0)));
    assertThat(r.page().getTotalItems()).isEqualTo(expected);
  }

  @Test
  void companyInIsOrAcrossValuesAndTotalIsExact() {
    SearchResponse r = run(req(null, List.of(in(LabelFieldKeys.COMPANY_NAME, "Cipla", "GSK")), null, List.of(), null, 0, 50, true));
    long expected = PRODUCTS_PER_COMPANY * LOTS_PER_PRODUCT * 2L;
    assertThat(r.page().getTotalItems()).isEqualTo(expected);
    assertThat(r.data()).hasSize(50);
  }

  @Test
  void facetsIgnoreTheirOwnFilterButRespectOthers() {
    SearchResponse r =
        run(req(null,
            List.of(in(LabelFieldKeys.COMPANY_NAME, "Cipla"), in(LabelFieldKeys.LOCATION, "J2")),
            null, List.of(LabelFieldKeys.COMPANY_NAME, LabelFieldKeys.LOCATION), null, 0, 10, true));
    // results: Cipla AND J2
    assertThat(r.page().getTotalItems()).isEqualTo(PRODUCTS_PER_COMPANY * LOTS_PER_PRODUCT / 4L);
    // company facet ignores the company filter → all three companies, each counted within J2 only
    Map<String, Long> company = facetMap(r, LabelFieldKeys.COMPANY_NAME);
    assertThat(company.keySet()).containsExactlyInAnyOrder("Cipla", "GSK", "Sun Pharma");
    assertThat(company.get("GSK")).isEqualTo(PRODUCTS_PER_COMPANY * LOTS_PER_PRODUCT / 4L);
    // location facet ignores the location filter → all four locations, each within Cipla only
    Map<String, Long> location = facetMap(r, LabelFieldKeys.LOCATION);
    assertThat(location.keySet()).containsExactlyInAnyOrder("J1", "J2", "J3", "J4");
    assertThat(location.get("J4")).isEqualTo(PRODUCTS_PER_COMPANY * LOTS_PER_PRODUCT / 4L);
  }

  @Test
  void enumFacetsListEveryValueIncludingZero() {
    SearchResponse r = run(req(null, List.of(in(LabelFieldKeys.STOCK_STATE, "LOW_STOCK")), null,
        List.of(LabelFieldKeys.STOCK_STATE, LabelFieldKeys.BILLING_MODE, "vertical.schedule"), null, 0, 10, true));
    List<FacetValue> stock = r.facets().get(LabelFieldKeys.STOCK_STATE);
    assertThat(stock).extracting(FacetValue::value).containsExactly("IN_STOCK", "LOW_STOCK", "SOLD_OUT");
    assertThat(stock.stream().filter(v -> v.value().equals("LOW_STOCK")).findFirst().orElseThrow().count())
        .isEqualTo(r.page().getTotalItems());
    // billing mode: null means REGULAR, so REGULAR + BASIC cover every LOW_STOCK lot
    Map<String, Long> billing = facetMap(r, LabelFieldKeys.BILLING_MODE);
    assertThat(billing.get("REGULAR") + billing.get("BASIC")).isEqualTo(r.page().getTotalItems());
    // vertical enum facet: all three schedules present as keys
    assertThat(facetMap(r, "vertical.schedule").keySet()).containsExactlyInAnyOrder("H", "H1", "OTC");
  }

  @Test
  void anyModeIsOrAcrossGroups() {
    SearchResponse all = run(req(null, List.of(in(LabelFieldKeys.COMPANY_NAME, "Cipla"), in(LabelFieldKeys.LOCATION, "J2")), "all", List.of(), null, 0, 10, true));
    SearchResponse any = run(req(null, List.of(in(LabelFieldKeys.COMPANY_NAME, "Cipla"), in(LabelFieldKeys.LOCATION, "J2")), "any", List.of(), null, 0, 10, true));
    long cipla = PRODUCTS_PER_COMPANY * LOTS_PER_PRODUCT;
    long j2 = 2400 / 4;
    assertThat(all.page().getTotalItems()).isEqualTo(cipla / 4);
    assertThat(any.page().getTotalItems()).isEqualTo(cipla + j2 - cipla / 4);
  }

  @Test
  void textPatternMatchesNameCompanyAndBatch() {
    SearchResponse byName = run(req("para*mol", List.of(), null, List.of(), null, 0, 10, true));
    assertThat(byName.page().getTotalItems()).isEqualTo(PRODUCTS_PER_COMPANY / 2 * LOTS_PER_PRODUCT * 3L);
    SearchResponse byCompany = run(req("sun", List.of(), null, List.of(), null, 0, 10, true));
    assertThat(byCompany.page().getTotalItems()).isEqualTo(PRODUCTS_PER_COMPANY * LOTS_PER_PRODUCT);
    SearchResponse byBatch = run(req("B0001", List.of(), null, List.of(), null, 0, 50, true));
    // batches B00010..B00019 exist except seq%9==0 (B00018 has no ext row)
    assertThat(byBatch.page().getTotalItems()).isEqualTo(9);
    SearchResponse nothing = run(req("zzzz", List.of(), null, List.of(), null, 0, 10, true));
    assertThat(nothing.page().getTotalItems()).isZero();
    assertThat(nothing.data()).isEmpty();
  }

  @Test
  void textAndFiltersCombine() {
    SearchResponse r = run(req("paracetamol", List.of(in(LabelFieldKeys.COMPANY_NAME, "GSK")), null, List.of(LabelFieldKeys.COMPANY_NAME), null, 0, 10, true));
    assertThat(r.page().getTotalItems()).isEqualTo(PRODUCTS_PER_COMPANY / 2 * LOTS_PER_PRODUCT);
    Map<String, Long> company = facetMap(r, LabelFieldKeys.COMPANY_NAME);
    // facet ignores the company filter but keeps the text: every company has paracetamol lots
    assertThat(company.values()).allMatch(v -> v == PRODUCTS_PER_COMPANY / 2 * LOTS_PER_PRODUCT);
  }

  @Test
  void expirySortPutsLotsWithoutExpiryLastAndPagesAreStable() {
    SearchResponse first = run(req(null, List.of(), null, List.of(), "expiryDate:asc", 0, 100, true));
    SearchResponse last = run(req(null, List.of(), null, List.of(), "expiryDate:asc", 23, 100, true));
    // the last page is the lots with no extension row (2400/9 ≈ 267 of them, so page 23 is all nulls)
    List<String> lastIds = last.data().stream().map(InventorySummaryDto::getId).toList();
    long nullExpiry = lastIds.stream().filter(id -> template.getCollection("inventory_ext_medical").countDocuments(new Document("inventoryId", id)) == 0).count();
    assertThat(nullExpiry).isEqualTo(lastIds.size());
    // first page: all have expiry and are ascending
    List<Date> dates = first.data().stream().map(s -> template.getCollection("inventory_ext_medical").find(new Document("inventoryId", s.getId())).first()).map(d -> d.getDate("expiryDate")).toList();
    assertThat(dates).doesNotContainNull();
    assertThat(dates).isSorted();
    // no overlap between page 0 and page 1
    SearchResponse second = run(req(null, List.of(), null, List.of(), "expiryDate:asc", 1, 100, true));
    assertThat(second.data().stream().map(InventorySummaryDto::getId)).doesNotContainAnyElementsOf(first.data().stream().map(InventorySummaryDto::getId).toList());
  }

  @Test
  void expiryWithinDaysAndBetween() {
    SearchResponse within = run(req(null, List.of(new FilterGroup(LabelFieldKeys.EXPIRY_DATE, "withinDays", List.of("30"), null, null)), null, List.of(), null, 0, 10, true));
    long expected = template.getCollection("inventory_ext_medical").countDocuments(
        new Document("expiryDate", new Document("$lte", Date.from(Instant.now().plus(30, ChronoUnit.DAYS))).append("$gte", Date.from(Instant.now()))));
    assertThat(within.page().getTotalItems()).isEqualTo(expected);
    SearchResponse exists = run(req(null, List.of(new FilterGroup(LabelFieldKeys.EXPIRY_DATE, "exists", List.of(), null, null)), null, List.of(), null, 0, 10, true));
    assertThat(exists.page().getTotalItems()).isEqualTo(template.getCollection("inventory_ext_medical").countDocuments());
  }

  @Test
  void everyMatchIsReachableByPagingNoCap() {
    long total = run(req("para*mol", List.of(), null, List.of(), null, 0, 200, true)).page().getTotalItems();
    assertThat(total).isGreaterThan(500); // more than the legacy ceiling
    int seen = 0;
    for (int page = 0; page * 200 < total; page++) {
      seen += run(req("para*mol", List.of(), null, List.of(), null, page, 200, true)).data().size();
    }
    assertThat(seen).isEqualTo(total);
  }

  @Test
  void regexModeWorksAndUnsafeIsRejected() {
    SearchResponse r = run(new SearchRequest("^Para.*mg$", "regex", List.of(), null, List.of(), null, 0, 10, true, null));
    assertThat(r.page().getTotalItems()).isGreaterThan(0);
    assertThatThrownBy(() -> run(new SearchRequest("(a+)+", "regex", List.of(), null, List.of(), null, 0, 10, true, null)))
        .isInstanceOf(ValidationException.class);
  }

  // ---- GET /inventory/search golden test (R8.1) --------------------------------------------------

  /**
   * The old query string, translated and run on the new engine, must find exactly the lots the old
   * search rules find: substring on name / company / location, prefix on barcode / HSN / batch, every
   * {@code key=value} an exact match, zero-stock lots included unless {@code includeZeroStock=false}.
   * The oracle below applies those rules with plain Mongo queries (no 500-row cap, which the old
   * code had).
   */
  @Test
  void legacyGetQueriesFindTheSameLotsAsTheOldRules() {
    List<Map<String, String>> queries =
        List.of(
            Map.of("q", "para"),
            Map.of("q", "Amoxicillin 300mg"),
            Map.of("q", "sun"),
            Map.of("q", "BC1"),
            Map.of("q", "B0002"),
            Map.of("q", "J3"),
            Map.of("q", "para", "companyName", "GSK"),
            Map.of("q", "cipla", "location", "J1", "includeZeroStock", "false"),
            Map.of("companyName", "Sun Pharma", "nearExpiryDays", "60"),
            Map.of("q", "30040", "includeZeroStock", "false"));
    ShopSearchContext ctx = catalogService.context(SHOP);
    for (Map<String, String> q : queries) {
      SearchRequest translated = LegacySearchQueryTranslator.translate(q, ctx);
      SearchRequest all = new SearchRequest(translated.text(), translated.textMode(), translated.filters(), translated.match(),
          List.of(), translated.sort(), 0, 200, translated.includeZeroStock(), translated.surface());
      Set<String> engineIds = new HashSet<>();
      long total = run(all).page().getTotalItems();
      for (int page = 0; page * 200 < total; page++) {
        run(new SearchRequest(all.text(), all.textMode(), all.filters(), all.match(), List.of(), all.sort(), page, 200, all.includeZeroStock(), all.surface()))
            .data().forEach(s -> engineIds.add(s.getId()));
      }
      assertThat(engineIds).as("query %s", q).containsExactlyInAnyOrderElementsOf(oracle(q));
    }
  }

  /** The old search's rules, applied directly. */
  private static Set<String> oracle(Map<String, String> q) {
    boolean includeZero = !"false".equals(q.get("includeZeroStock"));
    String text = q.get("q");
    Set<String> ids = new HashSet<>();
    Document lots = new Document("shopId", SHOP);
    if (!includeZero) lots.append("currentCount", new Document("$gt", 0));
    if (text != null) {
      String contains = InventoryFreeTextSearch.containsPattern(text);
      List<Document> productOr = new ArrayList<>();
      productOr.add(new Document("name", new Document("$regex", contains).append("$options", "i")));
      productOr.add(new Document("companyName", new Document("$regex", contains).append("$options", "i")));
      for (String token : InventoryFreeTextSearch.identifierTokens(text)) {
        productOr.add(new Document("barcode", new Document("$regex", InventoryFreeTextSearch.prefixPattern(token)).append("$options", "i")));
        productOr.add(new Document("hsn", new Document("$regex", InventoryFreeTextSearch.prefixPattern(token)).append("$options", "i")));
      }
      List<String> productIds = new ArrayList<>();
      template.getCollection("product").find(new Document("shopId", SHOP).append("$or", productOr)).forEach(p -> productIds.add(p.getObjectId("_id").toHexString()));
      List<Object> lotIds = new ArrayList<>();
      for (String token : InventoryFreeTextSearch.identifierTokens(text)) {
        template.getCollection("inventory_ext_medical")
            .find(new Document("shopId", SHOP).append("batchNo", new Document("$regex", InventoryFreeTextSearch.prefixPattern(token)).append("$options", "i")))
            .forEach(e -> lotIds.add(new ObjectId(e.getString("inventoryId"))));
      }
      List<Document> lotOr = new ArrayList<>();
      lotOr.add(new Document("location", new Document("$regex", contains).append("$options", "i")));
      lotOr.add(new Document("productId", new Document("$in", productIds)));
      lotOr.add(new Document("_id", new Document("$in", lotIds)));
      lots.append("$or", lotOr);
    }
    if (q.containsKey("companyName")) {
      List<String> productIds = new ArrayList<>();
      template.getCollection("product").find(new Document("shopId", SHOP).append("companyName", q.get("companyName"))).forEach(p -> productIds.add(p.getObjectId("_id").toHexString()));
      lots.append("productId", new Document("$in", productIds));
    }
    if (q.containsKey("location")) {
      lots.append("location", q.get("location"));
    }
    if (q.containsKey("nearExpiryDays")) {
      List<Object> lotIds = new ArrayList<>();
      Date to = Date.from(Instant.now().plus(Long.parseLong(q.get("nearExpiryDays")), ChronoUnit.DAYS));
      template.getCollection("inventory_ext_medical")
          .find(new Document("shopId", SHOP).append("expiryDate", new Document("$gte", new Date()).append("$lte", to)))
          .forEach(e -> lotIds.add(new ObjectId(e.getString("inventoryId"))));
      lots.append("_id", new Document("$in", lotIds));
    }
    template.getCollection("inventory").find(lots).forEach(l -> ids.add(l.getObjectId("_id").toHexString()));
    return ids;
  }

  @Test
  void valueSuggestionsCoverCountedAndOnePerProductFields() {
    SearchValueSuggester suggester = new SearchValueSuggester(template, catalogService);
    // counted field: from the cached distinct list, prefix first
    assertThat(suggester.suggest(SHOP, LabelFieldKeys.COMPANY_NAME, "s", null)).containsExactly("Sun Pharma", "GSK"); // prefix first, then contains
    assertThat(suggester.suggest(SHOP, LabelFieldKeys.COMPANY_NAME, "", null)).containsExactly("Cipla", "GSK", "Sun Pharma");
    // one-per-product fields: looked up live; names match anywhere, identifiers from the start
    List<String> names = suggester.suggest(SHOP, LabelFieldKeys.PRODUCT_NAME, "cetam", null);
    assertThat(names).isNotEmpty().allMatch(n -> n.toLowerCase().contains("cetam")).hasSizeLessThanOrEqualTo(20);
    assertThat(suggester.suggest(SHOP, LabelFieldKeys.BARCODE_TEXT, "BC1", 5)).hasSize(5).allMatch(b -> b.startsWith("BC1"));
    assertThat(suggester.suggest(SHOP, LabelFieldKeys.BARCODE_TEXT, "C1", 5)).as("no match in the middle of an identifier").isEmpty();
    assertThat(suggester.suggest(SHOP, LabelFieldKeys.HSN, "3004", null)).containsExactlyInAnyOrder("30040", "30041", "30042");
  }

  @Test
  void explainShowsIndexScanFirst() {
    ValidatedSearch v = new SearchRequestValidator().validate(
        req("para*mol", List.of(in(LabelFieldKeys.COMPANY_NAME, "Cipla")), null, List.of(LabelFieldKeys.COMPANY_NAME), null, 0, 20, false),
        catalogService.context(SHOP));
    SearchPlan plan = new InventorySearchPlanner(new MongoPreQueryRunner(template)).plan(v, catalogService.context(SHOP));
    Document explain = template.getDb().runCommand(
        new Document("explain", new Document("aggregate", "inventory").append("pipeline", plan.stages()).append("cursor", new Document()))
            .append("verbosity", "queryPlanner"));
    String json = explain.toJson();
    assertThat(json).contains("IXSCAN");
    long[] stats = engine.explainStats(plan);
    assertThat(stats[0]).isGreaterThanOrEqualTo(0);
  }
}
