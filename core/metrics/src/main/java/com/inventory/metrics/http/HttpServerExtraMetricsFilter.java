package com.inventory.metrics.http;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP meters for Grafana Cloud OTLP. Only {@link Counter}s — the same type as {@code
 * inventory_api_requests_total}, which already graphs. Micrometer {@link
 * io.micrometer.core.instrument.Timer}s become OTLP histograms; Grafana Cloud does not expose
 * Prometheus {@code _count}/{@code _bucket} for those, so RPS and p95 queries return no data.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class HttpServerExtraMetricsFilter extends OncePerRequestFilter {

  /** Same Counter name as {@link com.inventory.metrics.aspect.RequestRateAspect} (already graphs). */
  static final String REQUESTS = "inventory_api_requests_total";
  static final String DURATION = "inventory_http_duration_seconds_total";
  static final String LATENCY_BUCKET = "inventory_http_latency_bucket_total";

  /** Cumulative Prometheus-style latency buckets (seconds). */
  private static final double[] LATENCY_LE = {0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};

  private final MeterRegistry registry;
  private final AtomicInteger active = new AtomicInteger();

  public HttpServerExtraMetricsFilter(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder("http.server.active.requests", active, AtomicInteger::get)
        .description("In-flight HTTP requests")
        .register(registry);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (HttpServerModuleTags.skip(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    active.incrementAndGet();
    long startNanos = System.nanoTime();
    try {
      filterChain.doFilter(request, response);
    } finally {
      active.decrementAndGet();
      recordAfterRequest(startNanos, request, response);
    }
  }

  private void recordAfterRequest(
      long startNanos, HttpServletRequest request, HttpServletResponse response) {
    String method = HttpServerModuleTags.method(request);
    String uri = HttpServerModuleTags.uri(request);
    String module = HttpServerModuleTags.module(request);
    String status = String.valueOf(response.getStatus());
    double seconds = (System.nanoTime() - startNanos) / (double) TimeUnit.SECONDS.toNanos(1);

    Counter.builder(REQUESTS)
        .description("API request count")
        .tag("endpoint", uri)
        .tag("method", method)
        .tag("uri", uri)
        .tag("status", status)
        .tag("module", module)
        .register(registry)
        .increment();

    Counter.builder(DURATION)
        .description("Sum of HTTP request durations in seconds")
        .tag("method", method)
        .tag("uri", uri)
        .tag("status", status)
        .tag("module", module)
        .register(registry)
        .increment(seconds);

    for (double le : LATENCY_LE) {
      if (seconds <= le) {
        latencyBucket(method, uri, status, module, leLabel(le)).increment();
      }
    }
    latencyBucket(method, uri, status, module, "+Inf").increment();
  }

  private Counter latencyBucket(
      String method, String uri, String status, String module, String le) {
    return Counter.builder(LATENCY_BUCKET)
        .description("HTTP latency histogram buckets")
        .tag("method", method)
        .tag("uri", uri)
        .tag("status", status)
        .tag("module", module)
        .tag("le", le)
        .register(registry);
  }

  private static String leLabel(double le) {
    if (le == (long) le) {
      return Long.toString((long) le);
    }
    return Double.toString(le);
  }
}
