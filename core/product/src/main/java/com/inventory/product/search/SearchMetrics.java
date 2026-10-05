package com.inventory.product.search;

import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.search.ValidatedSearch.Clause;
import com.inventory.product.utils.constants.ProductMetricsConstants;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The search meters (advanced-product-search R11.1, R11.2). Every name starts with {@code
 * inventory_product_search_} and carries {@code module="product"}, so the product module dashboard
 * picks them up. All are Counters: that is the shape that reaches Grafana Cloud intact through the
 * OTLP export (see {@code HttpServerExtraMetricsFilter}), and latency is published as cumulative
 * {@code le} buckets so {@code histogram_quantile} works.
 *
 * <p>Tags never include shop, user or search values — only the shape of the request.
 */
@Component
@Slf4j
public class SearchMetrics {

  /** One per search, with its outcome: {@code ok, timeout, error, validation_error, regex_rejected}. */
  static final String REQUESTS = "inventory_product_search_requests_total";
  /** Sum of wall time in milliseconds; average = this / ok requests. */
  static final String DURATION_MS = "inventory_product_search_duration_ms_total";
  /** Sum of time spent in Mongo (pipeline + key reads) in milliseconds. */
  static final String DB_MS = "inventory_product_search_db_ms_total";
  /** Cumulative latency buckets in milliseconds ({@code le}). */
  static final String LATENCY_BUCKET = "inventory_product_search_latency_bucket_total";
  /** Sum of matched lots; average matches per search = this / ok requests. */
  static final String MATCHED = "inventory_product_search_matched_total";
  /** Sampled {@code explain}: documents Mongo examined vs returned (read amplification). */
  static final String DOCS_EXAMINED = "inventory_product_search_docs_examined_total";
  static final String DOCS_RETURNED = "inventory_product_search_docs_returned_total";
  /** Facets requested, by field. */
  static final String FACETS = "inventory_product_search_facets_total";
  /** Searches slower than {@link #SLOW_THRESHOLD_MS}. */
  static final String SLOW = "inventory_product_search_slow_total";

  static final long SLOW_THRESHOLD_MS = 500;
  static final int EXPLAIN_SAMPLE_EVERY = 50;
  static final long[] LATENCY_LE_MS = {50, 100, 250, 500, 1_000, 2_500, 5_000};

  private static final String MODULE = ProductMetricsConstants.MODULE;

  private final MetricsWrapper metrics;

  public SearchMetrics(MetricsWrapper metrics) {
    this.metrics = metrics;
  }

  /** The request shape tagged on the request counter. */
  public record Tags(String surface, String mode, boolean hasText, int filterCount) {
    static String bucket(int n) {
      if (n <= 0) return "0";
      if (n == 1) return "1";
      if (n <= 3) return "2-3";
      return "4+";
    }

    static Tags of(ValidatedSearch s) {
      return new Tags(
          s.surface(),
          s.text() != null ? s.text().mode().wireName() : TextMode.PATTERN.wireName(),
          s.hasText(),
          s.clauses().size());
    }
  }

  /** A completed search: outcome, timings, latency bucket, matches. */
  public void completed(Tags tags, long totalMillis, long dbMillis, long matched) {
    request(tags.surface(), "ok", tags.mode(), tags.hasText(), tags.filterCount());
    metrics.increment(DURATION_MS, totalMillis, "module", MODULE, "surface", tags.surface());
    metrics.increment(DB_MS, dbMillis, "module", MODULE, "surface", tags.surface());
    metrics.increment(MATCHED, matched, "module", MODULE, "surface", tags.surface());
    for (long le : LATENCY_LE_MS) {
      if (totalMillis <= le) {
        metrics.increment(LATENCY_BUCKET, 1, "module", MODULE, "surface", tags.surface(), "le", Long.toString(le));
      }
    }
    metrics.increment(LATENCY_BUCKET, 1, "module", MODULE, "surface", tags.surface(), "le", "+Inf");
  }

  /** A search that did not complete normally. */
  public void failed(String surface, String outcome) {
    request(surface, outcome, "-", false, 0);
  }

  private void request(String surface, String outcome, String mode, boolean hasText, int filters) {
    metrics.increment(
        REQUESTS, 1,
        "module", MODULE,
        "surface", surface,
        "outcome", outcome,
        "mode", mode,
        "text", Boolean.toString(hasText),
        "filters", Tags.bucket(filters));
  }

  public void facet(String fieldKey) {
    metrics.increment(FACETS, 1, "module", MODULE, "facet", fieldKey);
  }

  public void explainSample(String surface, long examined, long returned) {
    metrics.increment(DOCS_EXAMINED, Math.max(examined, 0), "module", MODULE, "surface", surface);
    metrics.increment(DOCS_RETURNED, Math.max(returned, 0), "module", MODULE, "surface", surface);
  }

  /** Counts and logs a slow search with its shape — field keys and operators, never values (R11.2). */
  public void slow(ValidatedSearch s, SearchPlan plan, long dbMillis, long totalMillis, long examined, long returned, String shopId) {
    metrics.increment(SLOW, 1, "module", MODULE, "surface", s.surface());
    String shape =
        s.clauses().stream()
            .map((Clause c) -> c.field().fieldKey() + ":" + c.op().wireName())
            .collect(Collectors.joining(","));
    log.warn(
        "[search-slow] surface={} mode={} hasText={} filters=[{}] match={} facets={} stages={} dbMs={} totalMs={} examined={} returned={} shop={}",
        s.surface(),
        s.text() != null ? s.text().mode().wireName() : "-",
        s.hasText(),
        shape,
        s.match().wireName(),
        s.facets().size(),
        plan.stages().size(),
        dbMillis,
        totalMillis,
        examined,
        returned,
        shopId);
  }

  public static boolean shouldSampleExplain(long counter) {
    return counter % EXPLAIN_SAMPLE_EVERY == 0;
  }
}
