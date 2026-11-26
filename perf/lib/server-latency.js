/**
 * Server-side latency from the backend's own histogram, shared by the k6 scripts.
 *
 * `HttpServerExtraMetricsFilter` (`core/metrics`) records
 * `inventory_http_latency_bucket_total{method, uri, le, status, module}` for every request, where
 * `uri` is the Spring path pattern (`/api/v1/hr/employees/{id}/pairing-codes`, not the concrete
 * path). A k6 script scrapes `/actuator/prometheus` in `setup()` and `teardown()`; the delta is
 * the histogram of that run, and the p95 read from it is the number the HR-module R10 and the
 * worker-app R14 bounds are about ("from request receipt to response sent").
 *
 * Pure functions only: no k6 import, so this file also loads under Node. The scrape itself
 * (`k6/http`) stays in each script.
 */

export const SERVER_METRIC = 'inventory_http_latency_bucket_total';

/**
 * Parses the Prometheus text exposition of `metric` into `{ "<method> <uri>": { "<le>": count } }`,
 * summed over `status` and `module`. Tolerates both text flavours Micrometer has produced
 * (`{a="b",c="d",}` with the trailing comma, and without).
 */
export function parseHistogram(body, metric = SERVER_METRIC) {
  const out = {};
  const prefix = `${metric}{`;
  for (const line of String(body).split('\n')) {
    if (!line.startsWith(prefix)) {
      continue;
    }
    // The last `}` closes the label block: `uri` values such as `/hr/employees/{id}/pairing-codes`
    // contain braces of their own, and the sample value after the block never does.
    const close = line.lastIndexOf('}');
    const labels = parseLabels(line.slice(prefix.length, close));
    const value = Number(line.slice(close + 1).trim().split(/\s+/)[0]);
    if (!labels.le || !labels.uri || !labels.method || !Number.isFinite(value)) {
      continue;
    }
    const key = rowKey(labels.method, labels.uri);
    const row = out[key] || (out[key] = {});
    row[labels.le] = (row[labels.le] || 0) + value;
  }
  return out;
}

export function parseLabels(text) {
  const labels = {};
  const re = /([A-Za-z_][A-Za-z0-9_]*)="((?:[^"\\]|\\.)*)"/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    labels[m[1]] = m[2].replace(/\\"/g, '"').replace(/\\\\/g, '\\');
  }
  return labels;
}

export function rowKey(method, uri) {
  return `${method} ${uri}`;
}

/** `end - start` per bucket; null when the row never appeared at the end. */
export function subtractBuckets(end, start) {
  if (!end) {
    return null;
  }
  const out = {};
  for (const le of Object.keys(end)) {
    out[le] = end[le] - ((start && start[le]) || 0);
  }
  return out;
}

/** Finite bucket bounds, ascending, each with the exact label Micrometer printed (`"0.5"`, `"1"`). */
export function finiteBounds(buckets) {
  return Object.keys(buckets)
    .filter((le) => le !== '+Inf')
    .map((label) => ({ label, value: Number(label) }))
    .filter((b) => Number.isFinite(b.value))
    .sort((a, b) => a.value - b.value);
}

/** Share of requests at or under `seconds`, read from the cumulative bucket with that bound. */
export function shareWithin(buckets, seconds) {
  const total = buckets['+Inf'] || 0;
  const bound = finiteBounds(buckets).find((b) => Math.abs(b.value - seconds) < 1e-9);
  if (bound === undefined || total === 0) {
    return NaN;
  }
  return buckets[bound.label] / total;
}

/** Prometheus-style linear interpolation inside the first bucket whose cumulative count reaches the rank. */
export function quantileMs(buckets, q) {
  const total = buckets['+Inf'] || 0;
  const rank = q * total;
  let lower = 0;
  let below = 0;
  for (const bound of finiteBounds(buckets)) {
    const cumulative = buckets[bound.label] || 0;
    if (cumulative >= rank) {
      const inBucket = cumulative - below;
      const seconds = inBucket > 0 ? lower + ((bound.value - lower) * (rank - below)) / inBucket : bound.value;
      return { ms: Math.round(seconds * 1000), text: String(Math.round(seconds * 1000)) };
    }
    lower = bound.value;
    below = cumulative;
  }
  return { ms: lower * 1000 + 1, text: `> ${lower * 1000}` };
}

export function pad(text, width) {
  const s = String(text);
  return s.length >= width ? `${s} ` : s + ' '.repeat(width - s.length);
}

/**
 * The table every script prints in `teardown()`, one row per target, and the gauge samples to
 * record. `targets` are `{target, method, uri, p95Ms}`; `baseline` and `end` come from
 * {@link parseHistogram}. Returns `{ lines, rows }` where each row is
 * `{ target, total, p95Ms, share }` (rows with no requests are left out of `rows`).
 */
export function latencyReport(targets, baseline, end) {
  const lines = [
    '',
    `server-side latency from ${SERVER_METRIC} (delta over this run, filter-level timer)`,
    pad('target', 18) + pad('method uri', 52) + pad('requests', 10) + pad('p95 ms', 10) + pad('share <= target', 17) + 'verdict',
  ];
  const rows = [];
  for (const t of targets) {
    const key = rowKey(t.method, t.uri);
    const delta = subtractBuckets(end[key], baseline[key]);
    const total = delta ? delta['+Inf'] || 0 : 0;
    if (total === 0) {
      lines.push(pad(t.target, 18) + pad(`${t.method} ${t.uri}`, 52) + pad('0', 10) + 'no requests in this run');
      continue;
    }
    const p95 = quantileMs(delta, 0.95);
    const share = shareWithin(delta, t.p95Ms / 1000);
    rows.push({ target: t.target, total, p95Ms: p95.ms, share });
    const verdict = share >= 0.95 ? `PASS (p95 <= ${t.p95Ms} ms)` : `FAIL (p95 > ${t.p95Ms} ms)`;
    lines.push(
      pad(t.target, 18) +
        pad(`${t.method} ${t.uri}`, 52) +
        pad(String(total), 10) +
        pad(p95.text, 10) +
        pad(`${(share * 100).toFixed(2)} %`, 17) +
        verdict,
    );
  }
  lines.push(
    '',
    'p95 is interpolated inside the bucket it falls in (Prometheus histogram_quantile style); the exact statement',
    'behind the verdict is "share <= target >= 95 %", read straight from the bucket with the target as its bound.',
  );
  return { lines, rows };
}
