package com.inventory.product.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.product.labels.LabelFieldKeys;
import com.inventory.product.rest.dto.request.SearchRequest;
import com.inventory.product.rest.dto.request.SearchRequest.FilterGroup;
import com.inventory.product.search.SearchFieldCatalogService.ShopSearchContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * Shape of the pipeline the planner builds (advanced-product-search R4.1, R5.2, R10.2), with the
 * pre-queries stubbed so no database is involved.
 */
class InventorySearchPlannerTest {

  private static final SearchRequestValidator VALIDATOR = new SearchRequestValidator();

  /** Pre-queries answered from maps: product filter JSON → ids, extension filter JSON → ids. */
  private static final class StubPreQueries implements PreQueryRunner {
    final List<Document> productFilters = new ArrayList<>();
    final List<Document> extensionFilters = new ArrayList<>();
    Function<Document, List<String>> products = f -> List.of("p1", "p2");
    Function<Document, List<String>> extensions = f -> List.of("i1", "i2", "i3");
    Map<String, List<String>> distinct = Map.of("location", List.of("J1", "J2", "K1"), "companyName", List.of("Cipla", "GSK"));

    @Override
    public List<String> distinctValues(String collection, String shopId, String field, int max) {
      return distinct.get(field);
    }

    @Override
    public List<String> productIds(String shopId, Document filter) {
      productFilters.add(filter);
      return products.apply(filter);
    }

    @Override
    public List<String> extensionInventoryIds(String collection, String shopId, Document filter) {
      extensionFilters.add(filter);
      return extensions.apply(filter);
    }
  }

  private static SearchPlan plan(ShopSearchContext ctx, StubPreQueries pre, SearchRequest r) {
    return new InventorySearchPlanner(pre).plan(VALIDATOR.validate(r, ctx), ctx);
  }

  private static SearchRequest req(String text, List<FilterGroup> filters, String match, List<String> facets, String sort) {
    return new SearchRequest(text, null, filters, match, facets, sort, 0, 20, false, "product-search");
  }

  private static FilterGroup in(String field, String... values) {
    return new FilterGroup(field, "in", List.of(values), null, null);
  }

  private static List<Document> stagesNamed(SearchPlan plan, String name) {
    return plan.stages().stream().filter(s -> s.containsKey(name)).toList();
  }

  private static Document firstMatch(SearchPlan plan) {
    return stagesNamed(plan, "$match").get(0).get("$match", Document.class);
  }

  @SuppressWarnings("unchecked")
  private static List<Document> facetBranch(SearchPlan plan, String output) {
    Document facet = stagesNamed(plan, "$facet").get(0).get("$facet", Document.class);
    return (List<Document>) facet.get(output);
  }

  // ---- stage order and narrowing ----------------------------------------------------------------

  @Test
  void firstStageMatchesShopAndStockBeforeAnythingElse() {
    SearchPlan plan = plan(SearchTestFixtures.pharmacy(), new StubPreQueries(), req(null, List.of(), null, List.of(), null));
    Document first = plan.stages().get(0);
    assertThat(first).containsKey("$match");
    assertThat(first.get("$match").toString()).contains("shopId").contains("currentCount");
    assertThat(stagesNamed(plan, "$lookup")).as("no join when nothing needs one").isEmpty();
    assertThat(plan.sortsInMemory()).as("default pharmacy sort is on the extension → deferred").isTrue();
  }

  @Test
  void productFilterBecomesProductIdInThroughAPreQuery() {
    StubPreQueries pre = new StubPreQueries();
    SearchPlan plan = plan(SearchTestFixtures.pharmacy(), pre, req(null, List.of(in(LabelFieldKeys.COMPANY_NAME, "Cipla", "GSK")), null, List.of(), "receivedDate:desc"));
    assertThat(pre.productFilters).hasSize(1);
    assertThat(pre.productFilters.get(0).toString()).contains("companyName");
    assertThat(firstMatch(plan).toString()).contains("productId").contains("$in").contains("p1");
    assertThat(stagesNamed(plan, "$lookup")).as("product join not needed to filter").isEmpty();
    assertThat(plan.sortsInMemory()).as("lot sort stays in Mongo").isFalse();
  }

  @Test
  void productFilterMatchingNothingShortCircuits() {
    StubPreQueries pre = new StubPreQueries();
    pre.products = f -> List.of();
    SearchPlan plan = plan(SearchTestFixtures.pharmacy(), pre, req(null, List.of(in(LabelFieldKeys.COMPANY_NAME, "Nobody")), null, List.of(LabelFieldKeys.LOCATION), null));
    assertThat(plan.shortCircuit()).isTrue();
    assertThat(plan.facetKeys()).containsExactly(LabelFieldKeys.LOCATION);
  }

  @Test
  void extensionFilterBecomesIdInAndNoJoin() {
    StubPreQueries pre = new StubPreQueries();
    SearchPlan plan = plan(SearchTestFixtures.pharmacy(), pre,
        req(null, List.of(new FilterGroup(LabelFieldKeys.EXPIRY_DATE, "withinDays", List.of("30"), null, null)), null, List.of(), "receivedDate:desc"));
    assertThat(pre.extensionFilters).hasSize(1);
    assertThat(pre.extensionFilters.get(0).toString()).contains("expiryDate").contains("$gte").contains("$lte");
    assertThat(firstMatch(plan).toString()).contains("_id").contains("i1");
    assertThat(plan.extensionJoined()).isFalse();
  }

  @Test
  void tooManyPreQueryIdsFallsBackToTheJoin() {
    StubPreQueries pre = new StubPreQueries();
    List<String> many = new ArrayList<>();
    for (int i = 0; i <= InventorySearchPlanner.MAX_PREFILTER_IDS; i++) many.add("i" + i);
    pre.extensions = f -> many;
    SearchPlan plan = plan(SearchTestFixtures.pharmacy(), pre,
        req(null, List.of(new FilterGroup(LabelFieldKeys.EXPIRY_DATE, "exists", List.of(), null, null)), null, List.of(), "receivedDate:desc"));
    assertThat(plan.extensionJoined()).isTrue();
    assertThat(stagesNamed(plan, "$lookup").get(0).get("$lookup", Document.class).getString("from")).isEqualTo("inventory_ext_medical");
  }

  @Test
  void shopWithoutVerticalNeverJoinsAnExtension() {
    SearchPlan plan = plan(SearchTestFixtures.plain(), new StubPreQueries(), req("para", List.of(in(LabelFieldKeys.LOCATION, "J1")), null, List.of(LabelFieldKeys.LOCATION), null));
    assertThat(plan.extensionJoined()).isFalse();
    assertThat(plan.stages().toString()).doesNotContain("inventory_ext_");
  }

  // ---- text ---------------------------------------------------------------------------------------

  @Test
  void textUsesDistinctValuesForLocationAndCompanyAndPrefixForIdentifiers() {
    StubPreQueries pre = new StubPreQueries();
    SearchPlan plan = plan(SearchTestFixtures.pharmacy(), pre, req("j", List.of(), null, List.of(), "receivedDate:desc"));
    String first = firstMatch(plan).toString();
    // location: distinct values J1, J2 match "j" → $in, no regex over every lot
    assertThat(first).contains("location").contains("J1").contains("J2").doesNotContain("K1");
    // product pre-query: name contains, company via distinct ($in would be empty → dropped), barcode/hsn prefix
    String productFilter = pre.productFilters.get(0).toString();
    assertThat(productFilter).contains("name").contains("barcode").contains("^j").contains("hsn");
    assertThat(productFilter).doesNotContain("companyName");
    // batch (extension in pharmacy) is a prefix match too
    assertThat(pre.extensionFilters.get(0).toString()).contains("batchNo").contains("^j");
  }

  @Test
  void textMatchingNothingAnywhereShortCircuits() {
    StubPreQueries pre = new StubPreQueries();
    pre.products = f -> List.of();
    pre.extensions = f -> List.of();
    pre.distinct = Map.of("location", List.of(), "companyName", List.of());
    SearchPlan plan = plan(SearchTestFixtures.pharmacy(), pre, req("zzz", List.of(), null, List.of(), null));
    assertThat(plan.shortCircuit()).isTrue();
  }

  // ---- facets -------------------------------------------------------------------------------------

  @Test
  void facetIgnoresItsOwnFilterButKeepsTheOthers() {
    SearchPlan plan = plan(SearchTestFixtures.pharmacy(), new StubPreQueries(),
        req(null, List.of(in(LabelFieldKeys.COMPANY_NAME, "Cipla"), in(LabelFieldKeys.LOCATION, "J2")), "all",
            List.of(LabelFieldKeys.COMPANY_NAME, LabelFieldKeys.LOCATION), "receivedDate:desc"));
    // both clauses are held back (both faceted), so the first match has neither
    String first = firstMatch(plan).toString();
    assertThat(first).doesNotContain("J2").doesNotContain("p1");
    // results apply both
    String results = facetBranch(plan, SearchPlan.RESULTS).toString();
    assertThat(results).contains("J2").contains("p1");
    // company facet applies only the location clause, grouped by productId (mapped later, no join)
    String company = facetBranch(plan, plan.facetOutputNames().get(LabelFieldKeys.COMPANY_NAME)).toString();
    assertThat(company).contains("J2").doesNotContain("p1").contains("$productId");
    assertThat(plan.productFacetPaths()).containsEntry(LabelFieldKeys.COMPANY_NAME, "companyName");
    // location facet applies only the company clause
    String location = facetBranch(plan, plan.facetOutputNames().get(LabelFieldKeys.LOCATION)).toString();
    assertThat(location).contains("p1").doesNotContain("J2").contains("$location");
  }

  @Test
  void anyModeOrsEveryClauseForResultsAndLeavesFacetsUnfiltered() {
    SearchPlan plan = plan(SearchTestFixtures.pharmacy(), new StubPreQueries(),
        req(null, List.of(in(LabelFieldKeys.COMPANY_NAME, "Cipla"), in(LabelFieldKeys.LOCATION, "J2")), "any",
            List.of(LabelFieldKeys.LOCATION), "receivedDate:desc"));
    assertThat(firstMatch(plan).toString()).doesNotContain("J2").doesNotContain("p1");
    List<Document> results = facetBranch(plan, SearchPlan.RESULTS);
    assertThat(results.get(0).get("$match", Document.class)).containsKey("$or");
    List<Document> location = facetBranch(plan, plan.facetOutputNames().get(LabelFieldKeys.LOCATION));
    assertThat(location.toString()).as("facets in any-mode count against the text only").doesNotContain("J2").doesNotContain("p1").doesNotContain("$or");
  }

  @Test
  void billingModeFacetCountsMissingValuesAsRegular() {
    SearchPlan plan = plan(SearchTestFixtures.pharmacy(), new StubPreQueries(), req(null, List.of(), null, List.of(LabelFieldKeys.BILLING_MODE), "receivedDate:desc"));
    String billing = facetBranch(plan, plan.facetOutputNames().get(LabelFieldKeys.BILLING_MODE)).toString();
    assertThat(billing).contains("$ifNull").contains("REGULAR");
    assertThat(billing).as("no $ne null pre-filter, or REGULAR lots would vanish").doesNotContain("$ne");
  }

  @Test
  void stockStateIsComputedOnlyWhenAskedFor() {
    SearchPlan without = plan(SearchTestFixtures.pharmacy(), new StubPreQueries(), req(null, List.of(), null, List.of(), "receivedDate:desc"));
    assertThat(without.stages().toString()).doesNotContain("stockState");
    SearchPlan with = plan(SearchTestFixtures.pharmacy(), new StubPreQueries(), req(null, List.of(in(LabelFieldKeys.STOCK_STATE, "LOW_STOCK")), null, List.of(), "receivedDate:desc"));
    assertThat(stagesNamed(with, "$addFields").get(0).get("$addFields", Document.class)).containsKey("stockState");
  }

  // ---- sort ---------------------------------------------------------------------------------------

  @Test
  void lotSortIsDoneInMongoWithNullsLastAndIdTiebreak() {
    SearchPlan plan = plan(SearchTestFixtures.pharmacy(), new StubPreQueries(), req(null, List.of(), null, List.of(), "receivedDate:desc"));
    List<Document> results = facetBranch(plan, SearchPlan.RESULTS);
    Document sort = results.stream().filter(s -> s.containsKey("$sort")).findFirst().orElseThrow().get("$sort", Document.class);
    assertThat(sort.keySet()).containsExactly(InventorySearchPlanner.SORT_NULL_FLAG, "createdAt", "_id");
    assertThat(sort.getInteger("createdAt")).isEqualTo(-1);
  }

  @Test
  void productAndExtensionSortsAreDeferredToTheEngine() {
    SearchPlan byName = plan(SearchTestFixtures.pharmacy(), new StubPreQueries(), req(null, List.of(), null, List.of(), "productName:asc"));
    assertThat(byName.deferredSort().collection()).isEqualTo("product");
    assertThat(byName.deferredSort().localKey()).isEqualTo("productId");
    assertThat(byName.deferredSort().valueField()).isEqualTo("normalizedName");
    SearchPlan byExpiry = plan(SearchTestFixtures.pharmacy(), new StubPreQueries(), req(null, List.of(), null, List.of(), "expiryDate:desc"));
    assertThat(byExpiry.deferredSort().collection()).isEqualTo("inventory_ext_medical");
    assertThat(byExpiry.deferredSort().localKey()).isEqualTo("_id");
    assertThat(byExpiry.deferredSort().ascending()).isFalse();
    // the results branch returns candidates only: no $sort / $skip, bounded by the candidate cap
    List<Document> results = facetBranch(byExpiry, SearchPlan.RESULTS);
    assertThat(results.toString()).doesNotContain("$sort").doesNotContain("$skip");
    assertThat(results.stream().filter(s -> s.containsKey("$limit")).findFirst().orElseThrow().getInteger("$limit"))
        .isEqualTo(InventorySearchPlanner.MAX_DEFERRED_CANDIDATES + 1);
  }
}
