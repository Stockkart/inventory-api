package com.inventory.product.search;

// Feature: advanced-product-search, Property 3: Validator totality
// Feature: advanced-product-search, Property 4: Legacy translation

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.labels.FieldUsage;
import com.inventory.product.labels.LabelFieldKeys;
import com.inventory.product.labels.PrintableField;
import com.inventory.product.rest.dto.request.SearchRequest;
import com.inventory.product.rest.dto.request.SearchRequest.FilterGroup;
import com.inventory.product.search.SearchFieldCatalogService.ShopSearchContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * <b>Validates: R2.3, R2.5, R2.6, R2.7, R4.4</b>
 *
 * <p>Well-formed requests validate to the same number of clauses with parsed values and defaults
 * filled; each injected fault is named in the single error message; the legacy GET translation
 * yields a request that validates and carries every parameter.
 */
class SearchRequestValidatorProperties {

  private static final ShopSearchContext PHARMACY = SearchTestFixtures.pharmacy();
  private static final ShopSearchContext SPORTS = SearchTestFixtures.sports();
  private final SearchRequestValidator validator =
      new SearchRequestValidator(Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC));

  @Property(tries = 150)
  void wellFormedRequestsValidate(@ForAll("good") SearchRequest req) {
    ValidatedSearch v = validator.validate(req, SPORTS);
    assertThat(v.clauses()).hasSameSizeAs(req.filters());
    assertThat(v.size()).isBetween(1, SearchRequestValidator.MAX_PAGE_SIZE);
    assertThat(v.page()).isGreaterThanOrEqualTo(0);
    assertThat(v.sort()).isNotNull(); // default sort always resolves
    assertThat(v.hasText()).isEqualTo(req.text() != null && !req.text().isBlank());
    for (ValidatedSearch.Clause c : v.clauses()) {
      switch (c.op()) {
        case IN -> assertThat(c.values()).isNotEmpty();
        case MATCHES -> assertThat(c.pattern()).isNotNull();
        case BETWEEN -> assertThat(c.fromNumber() != null || c.toNumber() != null || c.fromDate() != null || c.toDate() != null).isTrue();
        case WITHIN_DAYS -> {
          assertThat(c.fromDate()).isNotNull();
          assertThat(c.toDate()).isAfterOrEqualTo(c.fromDate());
        }
        case EXISTS -> assertThat(c.values()).isEmpty();
      }
    }
  }

  @Property(tries = 100)
  void everyFaultIsNamed(@ForAll("faults") Fault fault) {
    SearchRequest req = fault.inject();
    assertThatThrownBy(() -> validator.validate(req, PHARMACY))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining(fault.expected());
  }

  @Test
  void allFaultsReportedTogether() {
    SearchRequest req =
        new SearchRequest(
            null, "fuzzy",
            List.of(new FilterGroup("nope", "in", List.of("x"), null, null),
                new FilterGroup(LabelFieldKeys.STOCK_STATE, "in", List.of("PLENTY"), null, null)),
            "some", List.of("productName"), "mrp:asc", -1, 999, null, null);
    assertThatThrownBy(() -> validator.validate(req, PHARMACY))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("textMode must be")
        .hasMessageContaining("unknown search field 'nope'")
        .hasMessageContaining("[PLENTY] are not allowed")
        .hasMessageContaining("match must be")
        .hasMessageContaining("cannot show counts")
        .hasMessageContaining("sort: unknown search field 'mrp'")
        .hasMessageContaining("page must be 0 or more")
        .hasMessageContaining("size must be between");
  }

  @Test
  void pharmacyDefaultsRelocateExpiryToExtension() {
    assertThat(PHARMACY.defaultSort()).isEqualTo("expiryDate:asc");
    SearchSpec expiry = PHARMACY.spec(LabelFieldKeys.EXPIRY_DATE).orElseThrow();
    assertThat(expiry.source()).isEqualTo(SearchSource.EXTENSION);
    assertThat(expiry.path()).isEqualTo("ext.expiryDate");
    assertThat(expiry.sortable()).isTrue();
    SearchSpec batch = PHARMACY.spec(LabelFieldKeys.BATCH_NO).orElseThrow();
    assertThat(batch.path()).isEqualTo("ext.batchNo");
    // and no duplicate vertical.batchNo
    assertThat(PHARMACY.catalog().find("vertical.batchNo")).isEmpty();
    // plain shop: core paths and fallback sort
    ShopSearchContext plain = SearchTestFixtures.plain();
    assertThat(plain.defaultSort()).isEqualTo(SearchFieldCatalogService.FALLBACK_SORT);
    assertThat(plain.spec(LabelFieldKeys.EXPIRY_DATE).orElseThrow().path()).isEqualTo("expiryDate");
  }

  @Test
  void sportsEnumFieldIsFacetable() {
    SearchSpec sport = SPORTS.spec("vertical.sport").orElseThrow();
    assertThat(sport.type()).isEqualTo(SearchFieldType.ENUM);
    assertThat(sport.facetable()).isTrue();
    assertThat(sport.enumValues()).extracting(SearchSpec.EnumValue::value).contains("cricket", "gym");
    assertThat(SPORTS.spec("vertical.model")).isEmpty(); // not searchable
    assertThat(SPORTS.defaultSort()).isEqualTo("vertical.brand:asc");
  }

  // ---- legacy translation ------------------------------------------------------------------------

  @Property(tries = 100)
  void legacyQueryTranslatesAndValidates(@ForAll("legacyQueries") Map<String, String> query) {
    SearchRequest req = LegacySearchQueryTranslator.translate(query, SPORTS);
    assertThat(req.surface()).isEqualTo(LegacySearchQueryTranslator.SURFACE);
    assertThat(req.textMode()).isEqualTo("pattern");
    assertThat(req.match()).isEqualTo("all");
    String q = query.get("q");
    assertThat(req.text()).isEqualTo(q == null || q.isBlank() ? null : q.trim().replaceAll("\\s+", " "));
    long filterKeys = query.keySet().stream().filter(k -> !Set_RESERVED.contains(k)).count();
    assertThat(req.filters()).hasSize((int) filterKeys);
    ValidatedSearch v = validator.validate(req, SPORTS);
    assertThat(v.clauses()).hasSize((int) filterKeys);
    assertThat(v.includeZeroStock()).isEqualTo(!"false".equalsIgnoreCase(query.getOrDefault("includeZeroStock", "true")));
  }

  @Test
  void legacyExpiryKeysBecomeDateClauses() {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("q", "para");
    q.put("nearExpiryDays", "30");
    ValidatedSearch v = validator.validate(LegacySearchQueryTranslator.translate(q, PHARMACY), PHARMACY);
    assertThat(v.clauses()).hasSize(1);
    assertThat(v.clauses().get(0).op()).isEqualTo(FilterOp.WITHIN_DAYS);
    assertThat(v.clauses().get(0).field().fieldKey()).isEqualTo(LabelFieldKeys.EXPIRY_DATE);

    q = new LinkedHashMap<>();
    q.put("expiryBefore", "2027-01-01");
    q.put("expiryAfter", "2026-10-01T00:00:00Z");
    v = validator.validate(LegacySearchQueryTranslator.translate(q, PHARMACY), PHARMACY);
    assertThat(v.clauses().get(0).op()).isEqualTo(FilterOp.BETWEEN);
    assertThat(v.clauses().get(0).fromDate()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    assertThat(v.clauses().get(0).toDate()).isAfter(Instant.parse("2027-01-01T00:00:00Z"));
  }

  private static final java.util.Set<String> Set_RESERVED =
      java.util.Set.of("q", "sort", "limit", "cursor", "page", "size", "includeZeroStock");

  // ---- generators --------------------------------------------------------------------------------

  @Provide
  Arbitrary<SearchRequest> good() {
    List<PrintableField> fields = SPORTS.catalog().forUsage(FieldUsage.SEARCH);
    Arbitrary<FilterGroup> group =
        Arbitraries.of(fields)
            .flatMap(
                f -> {
                  SearchSpec s = f.searchSpec();
                  return Arbitraries.of(new ArrayList<>(s.operators()))
                      .map(op -> goodGroup(f, op));
                });
    Arbitrary<List<FilterGroup>> groups =
        group.list().uniqueElements(g -> g.field() + "/" + g.op()).ofMaxSize(5);
    Arbitrary<String> text = Arbitraries.oneOf(Arbitraries.just(null), Arbitraries.just(""), Arbitraries.of("para", "para*mol", "SKA7", "cip"));
    Arbitrary<String> match = Arbitraries.of("all", "any", null);
    Arbitrary<List<String>> facets =
        Arbitraries.of(fields.stream().filter(f -> f.searchSpec().facetable()).map(PrintableField::fieldKey).toList())
            .list().uniqueElements().ofMaxSize(SearchRequestValidator.MAX_FACETS);
    Arbitrary<String> sort =
        Arbitraries.oneOf(
            Arbitraries.just(null),
            Arbitraries.of(fields.stream().filter(f -> f.searchSpec().sortable()).map(PrintableField::fieldKey).toList())
                .flatMap(k -> Arbitraries.of(k + ":asc", k + ":desc", k)));
    Arbitrary<Integer> page = Arbitraries.integers().between(0, 20);
    Arbitrary<Integer> size = Arbitraries.oneOf(Arbitraries.just(null), Arbitraries.integers().between(1, 200));
    return Combinators.combine(text, groups, match, facets, sort, page, size)
        .as((t, g, m, fa, so, p, sz) -> new SearchRequest(t, "pattern", g, m, fa, so, p, sz, null, "product-search"))
        .filter(r -> (long) r.page() * (r.size() == null ? 20 : r.size()) <= SearchRequestValidator.MAX_OFFSET);
  }

  private static FilterGroup goodGroup(PrintableField f, FilterOp op) {
    SearchSpec s = f.searchSpec();
    return switch (op) {
      case IN -> new FilterGroup(
          f.fieldKey(), "in",
          s.type() == SearchFieldType.ENUM ? List.of(s.enumValues().get(0).value()) : List.of("Cipla", "GSK"),
          null, null);
      case MATCHES -> new FilterGroup(f.fieldKey(), "matches", List.of("ci*"), null, null);
      case EXISTS -> new FilterGroup(f.fieldKey(), "exists", List.of(), null, null);
      case WITHIN_DAYS -> new FilterGroup(f.fieldKey(), "withinDays", List.of("90"), null, null);
      case BETWEEN -> s.type() == SearchFieldType.NUMBER
          ? new FilterGroup(f.fieldKey(), "between", List.of(), "1", "100")
          : new FilterGroup(f.fieldKey(), "between", List.of(), "2026-01-01", null);
    };
  }

  /** One bad request and the fragment the message must contain. */
  record Fault(String expected, SearchRequest request) {
    SearchRequest inject() {
      return request;
    }
  }

  @Provide
  Arbitrary<Fault> faults() {
    return Arbitraries.of(
        new Fault("unknown search field 'nope'", withGroup(new FilterGroup("nope", "in", List.of("x"), null, null))),
        new Fault("operator 'between' is not allowed", withGroup(new FilterGroup(LabelFieldKeys.COMPANY_NAME, "between", List.of(), "1", "2"))),
        new Fault("operator is required", withGroup(new FilterGroup(LabelFieldKeys.COMPANY_NAME, "like", List.of("x"), null, null))),
        new Fault("'in' needs at least one value", withGroup(new FilterGroup(LabelFieldKeys.COMPANY_NAME, "in", List.of(), null, null))),
        new Fault("are not allowed", withGroup(new FilterGroup(LabelFieldKeys.BILLING_MODE, "in", List.of("PREMIUM"), null, null))),
        new Fault("needs exactly one pattern", withGroup(new FilterGroup(LabelFieldKeys.COMPANY_NAME, "matches", List.of("a", "b"), null, null))),
        new Fault("'from' and 'to' must be dates", withGroup(new FilterGroup(LabelFieldKeys.EXPIRY_DATE, "between", List.of(), "soon", null))),
        new Fault("'from' must not be after 'to'", withGroup(new FilterGroup(LabelFieldKeys.EXPIRY_DATE, "between", List.of(), "2027-01-01", "2026-01-01"))),
        new Fault("'between' needs 'from', 'to' or both", withGroup(new FilterGroup(LabelFieldKeys.CURRENT_COUNT, "between", List.of(), null, null))),
        new Fault("'from' and 'to' must be numbers", withGroup(new FilterGroup(LabelFieldKeys.CURRENT_COUNT, "between", List.of(), "ten", null))),
        new Fault("'withinDays' must be a whole number", withGroup(new FilterGroup(LabelFieldKeys.EXPIRY_DATE, "withinDays", List.of("-3"), null, null))),
        new Fault("appear twice", new SearchRequest(null, null,
            List.of(new FilterGroup(LabelFieldKeys.COMPANY_NAME, "in", List.of("a"), null, null),
                new FilterGroup(LabelFieldKeys.COMPANY_NAME, "in", List.of("b"), null, null)),
            null, null, null, null, null, null, null)),
        new Fault("textMode must be", new SearchRequest("x", "fuzzy", null, null, null, null, null, null, null, null)),
        new Fault("too long", new SearchRequest("a".repeat(121), "regex", null, null, null, null, null, null, null, null)),
        new Fault("repeats a group", new SearchRequest("(a+)+", "regex", null, null, null, null, null, null, null, null)),
        new Fault("match must be", new SearchRequest(null, null, null, "some", null, null, null, null, null, null)),
        new Fault("facets: unknown search field", new SearchRequest(null, null, null, null, List.of("nope"), null, null, null, null, null)),
        new Fault("cannot show counts", new SearchRequest(null, null, null, null, List.of(LabelFieldKeys.PRODUCT_NAME), null, null, null, null, null)),
        new Fault("sort: unknown search field", new SearchRequest(null, null, null, null, null, "nope:asc", null, null, null, null)),
        new Fault("cannot be sorted on", new SearchRequest(null, null, null, null, null, LabelFieldKeys.LOCATION + ":asc", null, null, null, null)),
        new Fault("direction must be asc or desc", new SearchRequest(null, null, null, null, null, LabelFieldKeys.COMPANY_NAME + ":up", null, null, null, null)),
        new Fault("size must be between", new SearchRequest(null, null, null, null, null, null, 0, 201, null, null)),
        new Fault("page must be 0 or more", new SearchRequest(null, null, null, null, null, null, -1, 10, null, null)),
        new Fault("must not exceed", new SearchRequest(null, null, null, null, null, null, 100, 200, null, null)));
  }

  private static SearchRequest withGroup(FilterGroup g) {
    return new SearchRequest(null, null, List.of(g), null, null, null, null, null, null, null);
  }

  @Provide
  Arbitrary<Map<String, String>> legacyQueries() {
    Arbitrary<String> q = Arbitraries.oneOf(Arbitraries.just(null), Arbitraries.of("para", "  para  mol ", "SKA"));
    Arbitrary<String> brand = Arbitraries.oneOf(Arbitraries.just(null), Arbitraries.of("Nike", "Adidas"));
    Arbitrary<String> sport = Arbitraries.oneOf(Arbitraries.just(null), Arbitraries.of("cricket", "gym"));
    Arbitrary<String> zero = Arbitraries.oneOf(Arbitraries.just(null), Arbitraries.of("false", "true", "FALSE"));
    Arbitrary<String> limit = Arbitraries.oneOf(Arbitraries.just(null), Arbitraries.of("10", "50"));
    Arbitrary<String> page = Arbitraries.oneOf(Arbitraries.just(null), Arbitraries.of("0", "2"));
    Arbitrary<String> sort = Arbitraries.oneOf(Arbitraries.just(null), Arbitraries.of("brand:asc", "vertical.brand:desc"));
    return Combinators.combine(q, brand, sport, zero, limit, page, sort)
        .as((a, b, c, d, e, f, g) -> {
          Map<String, String> m = new LinkedHashMap<>();
          if (a != null) m.put("q", a);
          if (b != null) m.put("brand", b);
          if (c != null) m.put("sport", c);
          if (d != null) m.put("includeZeroStock", d);
          if (e != null) m.put("limit", e);
          if (f != null) m.put("page", f);
          if (g != null) m.put("sort", g);
          return m;
        });
  }
}
