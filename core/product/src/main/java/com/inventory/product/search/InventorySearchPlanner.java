package com.inventory.product.search;

import com.inventory.product.labels.LabelFieldKeys;
import com.inventory.product.labels.PrintableField;
import com.inventory.product.search.SearchFieldCatalogService.ShopSearchContext;
import com.inventory.product.search.ValidatedSearch.Clause;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Turns a {@link ValidatedSearch} into one aggregation pipeline on {@code inventory}
 * (advanced-product-search R4.1, R4.4, R5.1, R5.2, R10.2).
 *
 * <p>The shape, in plain words:
 *
 * <ol>
 *   <li><b>Narrow on lots first.</b> {@code shopId}, the zero-stock rule, and every lot-side filter
 *       that can be applied before any join. Product-side filters and the search text are resolved
 *       to product ids by a cheap indexed pre-query and applied as {@code productId $in}, so the
 *       joins below run on hundreds of rows, not the whole shop. Text on a vertical field (pharmacy
 *       batch) is resolved the same way to inventory ids.
 *   <li><b>Join only when needed.</b> The product document is joined when a product field must be
 *       faceted or sorted on; the vertical document when a vertical field must be matched, faceted
 *       or sorted on. Stock state is computed with {@code $addFields} only when asked for.
 *   <li><b>Match what is left.</b> Remaining clauses in {@code all} mode are AND-ed here.
 *   <li><b>Fan out with {@code $facet}.</b> {@code results} sorts/skips/limits, {@code total}
 *       counts, and each requested facet groups by its field — after re-applying every held-back
 *       clause <i>except its own</i>, so a Company facet still lists the other companies after one
 *       is ticked. In {@code any} mode all clauses are one {@code $or} applied to results and total
 *       only; facets count against the text alone.
 * </ol>
 *
 * <p>The planner is pure apart from the two pre-queries, which go through {@link PreQueryRunner}.
 */
@Component
public class InventorySearchPlanner {

  static final String PRODUCT_ALIAS = "product";
  static final String EXT_ALIAS = "ext";
  static final String EXT_COLLECTION_PREFIX = "inventory_ext_";
  static final String SORT_NULL_FLAG = "_sortNull";
  static final String PRODUCT_KEY_FIELD = "_productOid";
  static final String EXT_KEY_FIELD = "_idStr";
  static final int FACET_VALUE_LIMIT = 25;

  /** Lot fields the search text is matched against directly on the inventory document. */
  private static final Set<String> TEXT_LOT_KEYS = Set.of(LabelFieldKeys.LOCATION, LabelFieldKeys.BATCH_NO);

  /** Product fields the search text is matched against via the product pre-query. */
  private static final Set<String> TEXT_PRODUCT_KEYS =
      Set.of(LabelFieldKeys.PRODUCT_NAME, LabelFieldKeys.COMPANY_NAME, LabelFieldKeys.BARCODE_TEXT, LabelFieldKeys.HSN);

  /** Vertical-stored fields the text is matched against via the extension pre-query. */
  private static final Set<String> TEXT_EXTENSION_KEYS = Set.of(LabelFieldKeys.BATCH_NO);

  /**
   * Fields with few distinct values per shop (a handful of racks, a few hundred companies). Instead of
   * running the regex over every row, the planner lists the distinct values with an index distinct
   * scan, keeps the ones the pattern matches, and turns the branch into an indexed {@code $in}.
   */
  private static final Set<String> TEXT_DISTINCT_KEYS = Set.of(LabelFieldKeys.LOCATION, LabelFieldKeys.COMPANY_NAME);

  /** Above this many distinct values the plain regex branch is used instead. */
  static final int MAX_DISTINCT_VALUES = 5_000;

  /**
   * Identifier-like fields are matched from the start of the value ("starts with"), as the previous
   * search did: that is what people mean when they type a barcode or batch, and an anchored regex
   * is checked in a fraction of the time of a substring scan.
   */
  private static final Set<String> TEXT_PREFIX_KEYS = Set.of(LabelFieldKeys.BARCODE_TEXT, LabelFieldKeys.HSN, LabelFieldKeys.BATCH_NO);

  /** Fields whose missing value means a default (grouping and matching treat null as it). */
  private static final Map<String, String> NULL_MEANS = Map.of(LabelFieldKeys.BILLING_MODE, "REGULAR");

  private final PreQueryRunner preQueries;

  public InventorySearchPlanner(PreQueryRunner preQueries) {
    this.preQueries = preQueries;
  }

  /** Above this many ids an {@code $in} pre-filter stops paying off and the join is used instead. */
  static final int MAX_PREFILTER_IDS = 20_000;

  /** Candidate sets above this size are sorted with a join instead of in memory (bounded memory). */
  static final int MAX_DEFERRED_CANDIDATES = 200_000;

  public SearchPlan plan(ValidatedSearch search, ShopSearchContext ctx) {
    return plan(search, ctx, false);
  }

  /**
   * @param forceJoinedSort sort through the join even for product/vertical keys (used when the
   *     candidate set is too large to order in memory)
   */
  public SearchPlan plan(ValidatedSearch search, ShopSearchContext ctx, boolean forceJoinedSort) {
    String shopId = ctx.shop().getShopId();
    String verticalId = ctx.shop().getVerticalId();
    boolean hasVertical = StringUtils.hasText(verticalId);
    String extCollection = hasVertical ? EXT_COLLECTION_PREFIX + verticalId : null;
    boolean anyMode = search.match() == MatchMode.ANY;

    Map<String, String> facetOutputs = new LinkedHashMap<>();
    Map<String, String> productFacetPaths = new LinkedHashMap<>();
    List<String> facetKeys = new ArrayList<>();
    for (PrintableField f : search.facets()) {
      facetKeys.add(f.fieldKey());
      facetOutputs.put(f.fieldKey(), "facet_" + f.fieldKey().replace('.', '_'));
    }

    // ---- resolve every clause to a condition on the lot document ---------------------------------
    // Product clauses become `productId $in` and extension clauses `_id $in` through cheap indexed
    // pre-queries, so neither join is needed to filter. A clause that matches nothing ends the search.
    Map<Clause, Document> resolved = new LinkedHashMap<>();
    boolean needExtForClauses = false;
    boolean needStock = false;
    for (Clause c : search.clauses()) {
      Resolved r = resolveClause(c, shopId, extCollection);
      if (r.empty() && !anyMode) {
        return SearchPlan.empty(facetKeys, facetOutputs);
      }
      if (r.empty()) {
        resolved.put(c, new Document("_id", new Document("$in", List.of()))); // matches nothing inside the $or
        continue;
      }
      if (r.document() == null) {
        needExtForClauses = true; // too many ids: fall back to matching on the joined path
        resolved.put(c, clauseDocument(c, c.path()));
      } else {
        resolved.put(c, r.document());
      }
      if (c.spec().source() == SearchSource.COMPUTED) {
        needStock = true;
      }
    }

    // Held back = applied inside $facet so a facet can ignore its own filter.
    // all-mode: clauses on faceted fields. any-mode: every clause (they form one $or).
    List<Clause> held = new ArrayList<>();
    List<Clause> common = new ArrayList<>();
    for (Clause c : search.clauses()) {
      if (anyMode || facetKeys.contains(c.field().fieldKey())) {
        held.add(c);
      } else {
        common.add(c);
      }
    }

    // ---- stage 1: narrow on lots ------------------------------------------------------------------
    List<Document> stages = new ArrayList<>();
    List<Document> first = new ArrayList<>();
    first.add(new Document("shopId", shopId));
    if (!search.includeZeroStock()) {
      first.add(new Document("currentCount", new Document("$gt", 0)));
    }
    if (search.hasText()) {
      Document textOr = textCondition(search.text(), ctx, shopId, extCollection);
      if (textOr == null) {
        return SearchPlan.empty(facetKeys, facetOutputs);
      }
      first.add(textOr);
    }
    // Common clauses that need nothing computed or joined go here, before anything else runs.
    List<Document> afterStock = new ArrayList<>();
    List<Document> afterJoin = new ArrayList<>();
    for (Clause c : common) {
      Document d = resolved.get(c);
      boolean onJoinedPath = d.containsKey(c.path()) && c.path().contains(".");
      if (onJoinedPath) {
        afterJoin.add(d);
      } else if (c.spec().source() == SearchSource.COMPUTED) {
        afterStock.add(d);
      } else {
        first.add(d);
      }
    }
    stages.add(new Document("$match", first.size() == 1 ? first.get(0) : new Document("$and", first)));

    // ---- stage 2: computed fields and joins, only when something needs them ---------------------------
    boolean facetsNeedStock = search.facets().stream().anyMatch(f -> f.searchSpec().source() == SearchSource.COMPUTED);
    if (needStock || facetsNeedStock) {
      stages.add(stockStateStage());
    }
    if (!afterStock.isEmpty()) {
      stages.add(new Document("$match", afterStock.size() == 1 ? afterStock.get(0) : new Document("$and", afterStock)));
    }

    SearchPlan.DeferredSort deferred = forceJoinedSort ? null : deferredSort(search, shopId, extCollection);
    boolean needProduct = deferred == null && sortNeeds(search, SearchSource.PRODUCT);
    boolean needExt =
        hasVertical
            && (needExtForClauses
                || (deferred == null && sortNeeds(search, SearchSource.EXTENSION))
                || search.facets().stream().anyMatch(f -> f.searchSpec().source() == SearchSource.EXTENSION));

    if (needProduct) {
      stages.add(new Document("$addFields", new Document(PRODUCT_KEY_FIELD, toObjectIdExpr("$productId"))));
      stages.add(lookup("product", PRODUCT_KEY_FIELD, "_id", PRODUCT_ALIAS));
      stages.add(unwind(PRODUCT_ALIAS));
    }
    if (needExt) {
      stages.add(new Document("$addFields", new Document(EXT_KEY_FIELD, new Document("$toString", "$_id"))));
      stages.add(lookup(extCollection, EXT_KEY_FIELD, "inventoryId", EXT_ALIAS));
      stages.add(unwind(EXT_ALIAS));
    }
    if (!afterJoin.isEmpty()) {
      stages.add(new Document("$match", afterJoin.size() == 1 ? afterJoin.get(0) : new Document("$and", afterJoin)));
    }

    // ---- stage 3: $facet ------------------------------------------------------------------------
    Document facet = new Document();
    Document heldMatchAll = heldMatch(held, resolved, null, anyMode);

    List<Document> results = new ArrayList<>();
    if (heldMatchAll != null) {
      results.add(new Document("$match", heldMatchAll));
    }
    if (deferred != null) {
      // every candidate, unsorted; the engine orders and pages (see SearchPlan.DeferredSort)
      results.add(new Document("$limit", MAX_DEFERRED_CANDIDATES + 1));
      results.add(new Document("$project", new Document("_id", 1).append(deferred.localKey(), 1)));
    } else {
      results.addAll(sortStages(search));
      results.add(new Document("$skip", search.skip()));
      results.add(new Document("$limit", search.size()));
      results.add(new Document("$project", new Document("_id", 1)));
    }
    facet.put(SearchPlan.RESULTS, results);

    List<Document> total = new ArrayList<>();
    if (heldMatchAll != null) {
      total.add(new Document("$match", heldMatchAll));
    }
    total.add(new Document("$count", "n"));
    facet.put(SearchPlan.TOTAL, total);

    for (PrintableField f : search.facets()) {
      List<Document> sub = new ArrayList<>();
      Document except = heldMatch(held, resolved, f.fieldKey(), anyMode);
      if (except != null) {
        sub.add(new Document("$match", except));
      }
      SearchSpec spec = f.searchSpec();
      if (spec.source() == SearchSource.PRODUCT && !needProduct) {
        // Group by productId; the engine maps ids to the product field with one indexed read.
        // Names are shown as written, not as the lowercase copy the search matches on.
        String path = stripAlias(spec.path(), PRODUCT_ALIAS);
        productFacetPaths.put(f.fieldKey(), path.equals("normalizedName") ? "name" : path);
        sub.add(new Document("$group", new Document("_id", "$productId").append("count", new Document("$sum", 1))));
      } else {
        String path = spec.path();
        if (!NULL_MEANS.containsKey(f.fieldKey())) {
          sub.add(new Document("$match", new Document(path, new Document("$ne", null))));
        }
        sub.add(new Document("$group", new Document("_id", groupKeyExpression(f)).append("count", new Document("$sum", 1))));
        sub.add(new Document("$sort", new Document("count", -1).append("_id", 1)));
        sub.add(new Document("$limit", FACET_VALUE_LIMIT));
      }
      facet.put(facetOutputs.get(f.fieldKey()), sub);
    }
    stages.add(new Document("$facet", facet));

    return new SearchPlan(stages, facetKeys, facetOutputs, needProduct, needExt, false, productFacetPaths, deferred);
  }

  /** A sort on a product or vertical field is deferred to the engine; lot-field sorts stay in Mongo. */
  private static SearchPlan.DeferredSort deferredSort(ValidatedSearch search, String shopId, String extCollection) {
    if (search.sort() == null) {
      return null;
    }
    SearchSpec spec = search.sort().field().searchSpec();
    boolean asc = search.sort().ascending();
    return switch (spec.source()) {
      case PRODUCT -> new SearchPlan.DeferredSort(
          SearchSource.PRODUCT, shopId, "product", "_id", "productId", stripAlias(spec.path(), PRODUCT_ALIAS), asc);
      case EXTENSION -> extCollection == null
          ? null
          : new SearchPlan.DeferredSort(
              SearchSource.EXTENSION, shopId, extCollection, "inventoryId", "_id", stripAlias(spec.path(), EXT_ALIAS), asc);
      default -> null;
    };
  }

  /** Outcome of resolving a clause: a lot-document condition, "nothing matches", or "use the join". */
  private record Resolved(Document document, boolean empty) {
    static Resolved of(Document d) {
      return new Resolved(d, false);
    }

    static Resolved nothing() {
      return new Resolved(null, true);
    }

    static Resolved join() {
      return new Resolved(null, false);
    }
  }

  private Resolved resolveClause(Clause c, String shopId, String extCollection) {
    SearchSource source = c.spec().source();
    switch (source) {
      case LOT, COMPUTED -> {
        return Resolved.of(clauseDocument(c, c.path()));
      }
      case PRODUCT -> {
        List<String> ids = preQueries.productIds(shopId, clauseDocument(c, stripAlias(c.path(), PRODUCT_ALIAS)));
        if (ids.isEmpty()) return Resolved.nothing();
        if (ids.size() > MAX_PREFILTER_IDS) return Resolved.join();
        return Resolved.of(new Document("productId", new Document("$in", ids)));
      }
      case EXTENSION -> {
        if (extCollection == null) return Resolved.nothing();
        List<String> ids =
            preQueries.extensionInventoryIds(extCollection, shopId, clauseDocument(c, stripAlias(c.path(), EXT_ALIAS)));
        if (ids.isEmpty()) return Resolved.nothing();
        if (ids.size() > MAX_PREFILTER_IDS) return Resolved.join();
        return Resolved.of(new Document("_id", new Document("$in", toIdValues(ids))));
      }
    }
    throw new IllegalStateException("Unhandled source " + source);
  }

  // ---- text ----------------------------------------------------------------------------------------

  /**
   * One {@code $or} on the inventory document covering every text field: lot fields by regex,
   * product fields through product ids, vertical fields through inventory ids. Names and companies
   * match anywhere in the value; identifiers match from the start; low-cardinality fields become an
   * {@code $in} of their matching distinct values. Returns {@code null} when nothing at all can match.
   */
  private Document textCondition(TextPattern text, ShopSearchContext ctx, String shopId, String extCollection) {
    List<Document> or = new ArrayList<>();
    Pattern compiled = text.compiled();

    List<Document> productOr = new ArrayList<>();
    Document extFilterOr = null;
    List<Document> extOr = new ArrayList<>();
    for (PrintableField f : ctx.catalog().forUsage(com.inventory.product.labels.FieldUsage.SEARCH)) {
      SearchSpec s = f.searchSpec();
      if (s.type() != SearchFieldType.TEXT) {
        continue;
      }
      String key = f.fieldKey();
      Document regex = TEXT_PREFIX_KEYS.contains(key) ? prefixRegexDoc(text) : regexDoc(text);
      switch (s.source()) {
        case PRODUCT -> {
          if (TEXT_PRODUCT_KEYS.contains(key)) {
            // match on the raw name, not normalizedName, so the user's pattern applies as typed
            String path = stripAlias(s.path(), PRODUCT_ALIAS);
            path = path.equals("normalizedName") ? "name" : path;
            Document branch = TEXT_DISTINCT_KEYS.contains(key) ? distinctBranch("product", shopId, path, compiled, regex) : new Document(path, regex);
            if (branch != null) {
              productOr.add(branch);
            }
          }
        }
        case LOT -> {
          if (TEXT_LOT_KEYS.contains(key)) {
            Document branch =
                TEXT_DISTINCT_KEYS.contains(key) ? distinctBranch("inventory", shopId, s.path(), compiled, regex) : new Document(s.path(), regex);
            if (branch != null) {
              or.add(branch);
            }
          }
        }
        case EXTENSION -> {
          if (TEXT_EXTENSION_KEYS.contains(key) && extCollection != null) {
            extOr.add(new Document(stripAlias(s.path(), EXT_ALIAS), regex));
          }
        }
        default -> {}
      }
    }
    if (!productOr.isEmpty()) {
      List<String> productIds = preQueries.productIds(shopId, new Document("$or", productOr));
      if (!productIds.isEmpty()) {
        or.add(new Document("productId", new Document("$in", productIds)));
      }
    }
    if (!extOr.isEmpty()) {
      extFilterOr = new Document("$or", extOr);
      List<String> invIds = preQueries.extensionInventoryIds(extCollection, shopId, extFilterOr);
      if (!invIds.isEmpty()) {
        or.add(new Document("_id", new Document("$in", toIdValues(invIds))));
      }
    }
    if (or.isEmpty()) {
      return null;
    }
    return or.size() == 1 ? or.get(0) : new Document("$or", or);
  }

  /**
   * {@code {path: {$in: [matching distinct values]}}}, {@code null} when no value matches, or the
   * plain regex branch when the field turns out to have too many distinct values.
   */
  private Document distinctBranch(String collection, String shopId, String path, Pattern compiled, Document regex) {
    List<String> values = preQueries.distinctValues(collection, shopId, path, MAX_DISTINCT_VALUES);
    if (values == null) {
      return new Document(path, regex);
    }
    List<String> matching = values.stream().filter(v -> compiled.matcher(v).find()).toList();
    return matching.isEmpty() ? null : new Document(path, new Document("$in", matching));
  }

  // ---- clauses -------------------------------------------------------------------------------------

  /** One clause as a Mongo match document on the given path. */
  static Document clauseDocument(Clause c, String path) {
    SearchSpec spec = c.spec();
    String key = c.field().fieldKey();
    switch (c.op()) {
      case IN -> {
        if (spec.type() == SearchFieldType.TEXT) {
          List<Pattern> exact = c.values().stream().map(v -> Pattern.compile("^" + Pattern.quote(v) + "$", Pattern.CASE_INSENSITIVE)).toList();
          return new Document(path, new Document("$in", exact));
        }
        String nullMeans = NULL_MEANS.get(key);
        Document in = new Document(path, new Document("$in", c.values()));
        if (nullMeans != null && c.values().contains(nullMeans)) {
          return new Document("$or", List.of(in, new Document(path, null)));
        }
        return in;
      }
      case MATCHES -> {
        return new Document(path, regexDoc(c.pattern()));
      }
      case EXISTS -> {
        return new Document(path, new Document("$ne", null));
      }
      case BETWEEN, WITHIN_DAYS -> {
        Document range = new Document();
        if (c.fromNumber() != null) range.put("$gte", c.fromNumber());
        if (c.toNumber() != null) range.put("$lte", c.toNumber());
        if (c.fromDate() != null) range.put("$gte", java.util.Date.from(c.fromDate()));
        if (c.toDate() != null) range.put("$lte", java.util.Date.from(c.toDate()));
        return new Document(path, range);
      }
    }
    throw new IllegalStateException("Unhandled operator " + c.op());
  }

  /**
   * The match applied inside {@code $facet}: in {@code all} mode the AND of held-back clauses minus
   * the one on {@code exceptField}; in {@code any} mode the OR of every clause for results/total and
   * nothing for facets. {@code null} when there is nothing to apply.
   */
  static Document heldMatch(List<Clause> held, Map<Clause, Document> resolved, String exceptField, boolean anyMode) {
    if (held.isEmpty()) {
      return null;
    }
    if (anyMode) {
      if (exceptField != null) {
        return null; // facets in any-mode count against the text only
      }
      List<Document> or = held.stream().map(resolved::get).toList();
      return or.size() == 1 ? or.get(0) : new Document("$or", or);
    }
    List<Document> and = new ArrayList<>();
    for (Clause c : held) {
      if (exceptField == null || !c.field().fieldKey().equals(exceptField)) {
        and.add(resolved.get(c));
      }
    }
    if (and.isEmpty()) {
      return null;
    }
    return and.size() == 1 ? and.get(0) : new Document("$and", and);
  }

  // ---- sort ----------------------------------------------------------------------------------------

  /** Nulls last, then the field, then {@code _id} for a stable order. */
  static List<Document> sortStages(ValidatedSearch search) {
    List<Document> out = new ArrayList<>();
    if (search.sort() == null) {
      out.add(new Document("$sort", new Document("createdAt", -1).append("_id", 1)));
      return out;
    }
    String path = search.sort().path();
    int dir = search.sort().ascending() ? 1 : -1;
    out.add(
        new Document(
            "$addFields",
            new Document(
                SORT_NULL_FLAG,
                new Document(
                    "$cond",
                    Arrays.asList(
                        new Document("$eq", Arrays.asList(new Document("$ifNull", Arrays.asList("$" + path, null)), null)),
                        1,
                        0)))));
    out.add(new Document("$sort", new Document(SORT_NULL_FLAG, 1).append(path, dir).append("_id", 1)));
    return out;
  }

  // ---- helpers -------------------------------------------------------------------------------------

  private static boolean sortNeeds(ValidatedSearch search, SearchSource source) {
    return search.sort() != null && search.sort().field().searchSpec().source() == source;
  }

  /** Classic equality join; uses the index on {@code foreignField}. */
  private static Document lookup(String from, String localField, String foreignField, String as) {
    return new Document(
        "$lookup",
        new Document("from", from)
            .append("localField", localField)
            .append("foreignField", foreignField)
            .append("as", as));
  }

  /** {@code $convert} to ObjectId that yields null (not an error) for a malformed id. */
  private static Document toObjectIdExpr(String input) {
    return new Document(
        "$convert",
        new Document("input", input).append("to", "objectId").append("onError", null).append("onNull", null));
  }

  private static Document unwind(String path) {
    return new Document("$unwind", new Document("path", "$" + path).append("preserveNullAndEmptyArrays", true));
  }

  private static Document stockStateStage() {
    Document current = new Document("$ifNull", List.of("$currentCount", 0));
    Document threshold = new Document("$ifNull", List.of("$thresholdCount", 0));
    Document soldOut = new Document("case", new Document("$lte", List.of(current, 0))).append("then", "SOLD_OUT");
    Document low =
        new Document(
                "case",
                new Document("$and", List.of(new Document("$gt", List.of(threshold, 0)), new Document("$lte", List.of(current, threshold)))))
            .append("then", "LOW_STOCK");
    Document sw = new Document("$switch", new Document("branches", List.of(soldOut, low)).append("default", "IN_STOCK"));
    return new Document("$addFields", new Document(LabelFieldKeys.STOCK_STATE, sw));
  }

  private static Object groupKeyExpression(PrintableField f) {
    String path = f.searchSpec().path();
    String nullMeans = NULL_MEANS.get(f.fieldKey());
    if (nullMeans != null) {
      return new Document("$ifNull", List.of("$" + path, nullMeans));
    }
    return "$" + path;
  }

  static Document regexDoc(TextPattern p) {
    return new Document("$regex", p.source()).append("$options", "i");
  }

  static Document prefixRegexDoc(TextPattern p) {
    return new Document("$regex", p.prefixSource()).append("$options", "i");
  }

  static String stripAlias(String path, String alias) {
    return path.startsWith(alias + ".") ? path.substring(alias.length() + 1) : path;
  }

  /** Inventory ids may be stored as ObjectId or string; match both shapes. */
  private static List<Object> toIdValues(List<String> ids) {
    List<Object> out = new ArrayList<>(ids.size() * 2);
    for (String id : ids) {
      out.add(id);
      if (ObjectId.isValid(id)) {
        out.add(new ObjectId(id));
      }
    }
    return out;
  }

}
