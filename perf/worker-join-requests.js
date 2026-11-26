/**
 * k6 load test for the manager's Join requests inbox (worker-app task 7.5, R14.4).
 *
 *   k6 run perf/worker-join-requests.js
 *
 * Needs k6 >= 1.0 and a fixture written by `perf/seed-worker-join-requests.mjs`: shops with 200
 * pending join requests each, their owner logins, and the pending request ids.
 *
 * Scenarios, one after the other (the list must see the full 200-pending inbox, and approving
 * drains it):
 *   inbox_list    LIST_RATE/s (default 10) for LIST_DURATION (default 2m): manager
 *                 `GET /hr/join-requests?status=PENDING&page=0&size=50`, shops round-robin.
 *                 Threshold p95 < 1 s.
 *   bulk_approve  starts when inbox_list is over; `POST /hr/join-requests/bulk/approve {ids}` with
 *                 exactly 50 ids per request until every batch in the fixture is used, APPROVE_VUS
 *                 (default 1) at a time. Threshold p95 < 5 s.
 *
 * How approvals never collide: the fixture's pending ids are cut into batches of 50 at init time,
 * ordered round-robin over the shops (batch 0 of shop A, batch 0 of shop B, …, batch 1 of shop A, …).
 * The scenario is a `shared-iterations` executor with exactly `batches.length` iterations, and each
 * iteration takes `batches[exec.scenario.iterationInTest]`: k6 numbers the iterations of a scenario
 * 0..n-1 once across every VU, so every batch is sent once, by one VU, and no id appears in two
 * requests. With APPROVE_VUS > 1 the round-robin order puts concurrent requests on different shops;
 * two batches of the same shop in flight at once would take turns on the shop's transaction lock
 * (`MongoTx`, AGENTS P8) and the second one would measure the first one's time too.
 *
 * Two kinds of latency are reported, as in `attendance-app-punches.js`:
 *   - k6's `http_req_duration` per scenario (client side). Thresholds: inbox_list p95 < 1000 ms,
 *     bulk_approve p95 < 5000 ms.
 *   - The server's own timer, `inventory_http_latency_bucket_total{method, uri, le}` from
 *     `HttpServerExtraMetricsFilter`, scraped in `setup()` and `teardown()`; the delta is this
 *     run's histogram and the p95 read from it is the authoritative number (R14.4 says
 *     "server-side"), recorded as `server_p95_ms{target}` with the same thresholds and printed as
 *     a table. The bucket bounds include 1 s and 5 s, so the share under each target is exact.
 *
 * Environment: BASE_URL (overrides the fixture's), FIXTURE (path), LIST_DURATION, LIST_RATE,
 * APPROVE_VUS, MAX_BATCHES (cap on the batches approved; default all), METRICS_URL (default
 * BASE_URL origin + /actuator/prometheus; `off` skips the server-side read). Self-signed TLS:
 * `k6 run --insecure-skip-tls-verify`.
 */
import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Counter, Gauge, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';

import { parseBaseUrl } from './lib/attendance-protocol.js';
import { SERVER_METRIC, latencyReport, parseHistogram } from './lib/server-latency.js';

// ---- init: fixture, URLs, options -----------------------------------------------------------------

const FIXTURE_PATH = __ENV.FIXTURE || './worker-join-requests-fixture.json';
const fixture = JSON.parse(open(FIXTURE_PATH));
if (!fixture || !Array.isArray(fixture.shops) || fixture.shops.length === 0) {
  throw new Error(`${FIXTURE_PATH} has no shops; run perf/seed-worker-join-requests.mjs first`);
}

const BASE_URL = __ENV.BASE_URL || fixture.baseUrl;
const base = parseBaseUrl(BASE_URL);
const ORIGIN = base.origin;
const BASE_PATH = base.basePath; // e.g. /api/v1 — the `uri` tag starts with it
const METRICS_URL =
  __ENV.METRICS_URL === undefined ? `${ORIGIN}/actuator/prometheus` : __ENV.METRICS_URL;

const LIST_DURATION = __ENV.LIST_DURATION || '2m';
const LIST_RATE = Number(__ENV.LIST_RATE || 10);
const APPROVE_VUS = Number(__ENV.APPROVE_VUS || 1);
const MAX_BATCHES = __ENV.MAX_BATCHES === undefined ? Infinity : Number(__ENV.MAX_BATCHES);

/** `JoinRequestInboxValidator.MAX_BULK`: the most ids one bulk call accepts, and the R14.4 figure. */
const BATCH_SIZE = 50;
/** The page the inbox renders by default; the R14.4 bound is for a shop with 200 pending. */
const LIST_PAGE_SIZE = 50;
/** What the seeder was asked to leave pending per shop; the list check expects at least this many. */
const EXPECTED_PENDING = Number(fixture.requestsPerShop || 200);

const shops = new SharedArray('shops', () =>
  fixture.shops.map(({ shopId, ownerEmail }) => ({ shopId, ownerEmail })),
);

/** Batches of exactly BATCH_SIZE ids, round-robin over the shops; a shop's remainder (< 50) is left out. */
const batches = new SharedArray('batches', () => {
  const perShop = fixture.shops.map((shop) => {
    const out = [];
    const ids = shop.pendingIds || [];
    for (let i = 0; i + BATCH_SIZE <= ids.length; i += BATCH_SIZE) {
      out.push({ shopId: shop.shopId, ids: ids.slice(i, i + BATCH_SIZE) });
    }
    return out;
  });
  const out = [];
  const longest = Math.max(0, ...perShop.map((b) => b.length));
  for (let round = 0; round < longest; round++) {
    for (const shopBatches of perShop) {
      if (round < shopBatches.length) out.push(shopBatches[round]);
    }
  }
  return out.slice(0, Math.min(out.length, MAX_BATCHES));
});
if (batches.length === 0) {
  throw new Error(`${FIXTURE_PATH} has no shop with ${BATCH_SIZE} pending ids; re-run the seeder`);
}

const SERVER_TARGETS = [
  { target: 'inbox_list', method: 'GET', uri: `${BASE_PATH}/hr/join-requests`, p95Ms: 1000 },
  { target: 'bulk_approve', method: 'POST', uri: `${BASE_PATH}/hr/join-requests/bulk/approve`, p95Ms: 5000 },
];

const serverP95 = new Gauge('server_p95_ms');
const serverShareWithinTarget = new Gauge('server_share_within_target');
const serverRequests = new Gauge('server_requests');
const inboxRows = new Counter('inbox_rows');
const bulkOutcomes = new Counter('bulk_outcomes');
const answered = new Rate('answered'); // 2xx or a 4xx refusal: the server produced an answer

// A refusal (4xx with the API error envelope) is an answer; only 5xx and transport errors are
// failures for `http_req_failed`.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

const serverThresholds = {};
for (const t of SERVER_TARGETS) {
  serverThresholds[`server_p95_ms{target:${t.target}}`] = [`value<${t.p95Ms}`];
}

/** Generous ceiling for the approve scenario: every batch at its threshold, serially, plus slack. */
const approveMaxDuration = `${Math.ceil((batches.length * 5) / Math.max(1, APPROVE_VUS)) + 120}s`;

export const options = {
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    inbox_list: {
      executor: 'constant-arrival-rate',
      exec: 'inboxList',
      rate: LIST_RATE,
      timeUnit: '1s',
      duration: LIST_DURATION,
      preAllocatedVUs: Math.max(5, Math.ceil(LIST_RATE * 0.8)),
      maxVUs: Math.max(20, LIST_RATE * 4),
      gracefulStop: '30s',
    },
    bulk_approve: {
      executor: 'shared-iterations',
      exec: 'bulkApprove',
      vus: APPROVE_VUS,
      iterations: batches.length,
      startTime: `${durationSeconds(LIST_DURATION) + 35}s`, // after inbox_list and its gracefulStop
      maxDuration: approveMaxDuration,
      gracefulStop: '30s',
    },
  },
  thresholds: Object.assign(
    {
      // Client side (includes network); the server-side numbers below are authoritative.
      'http_req_duration{scenario:inbox_list}': ['p(95)<1000'],
      'http_req_duration{scenario:bulk_approve}': ['p(95)<5000'],
      http_req_failed: ['rate<0.01'],
      answered: ['rate>0.99'],
      'checks{scenario:inbox_list}': ['rate>0.99'],
      'checks{scenario:bulk_approve}': ['rate>0.99'],
    },
    serverThresholds,
  ),
};

/** `30s`, `2m`, `1h30m`, or a bare number of seconds → whole seconds (k6 duration subset). */
function durationSeconds(text) {
  const s = String(text).trim();
  if (/^\d+$/.test(s)) return Number(s);
  const re = /(\d+)(h|m|s)/g;
  let total = 0;
  let matched = '';
  let m;
  while ((m = re.exec(s)) !== null) {
    matched += m[0];
    total += Number(m[1]) * (m[2] === 'h' ? 3600 : m[2] === 'm' ? 60 : 1);
  }
  if (matched !== s) {
    throw new Error(`LIST_DURATION must look like 30s, 2m or 1h30m, got ${text}`);
  }
  return total;
}

// ---- helpers -----------------------------------------------------------------------------------------

function bearer(token, shopId, name) {
  return {
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      'X-Shop-Id': shopId,
    },
    tags: { name },
  };
}

function jsonData(res) {
  try {
    const payload = res.json();
    return payload && payload.success === true ? payload.data : null;
  } catch (e) {
    return null;
  }
}

function errorText(res) {
  try {
    const payload = res.json();
    return (payload && (payload.message || payload.error)) || `status ${res.status}`;
  } catch (e) {
    return `status ${res.status}`;
  }
}

function tokenFor(data, shopId) {
  const token = data.tokens[shopId];
  if (!token) {
    fail(`no manager token for shop ${shopId}; setup() login failed`);
  }
  return token;
}

// ---- scenario: inbox list (R14.4, 200 pending, first page of 50) ----------------------------------------

export function inboxList(data) {
  const shop = shops[exec.scenario.iterationInTest % shops.length];
  const res = http.get(
    `${BASE_URL}/hr/join-requests?status=PENDING&page=0&size=${LIST_PAGE_SIZE}`,
    bearer(tokenFor(data, shop.shopId), shop.shopId, 'GET /hr/join-requests'),
  );
  answered.add(res.status >= 200 && res.status < 500);
  const page = jsonData(res);
  if (page && Array.isArray(page.items)) {
    inboxRows.add(page.items.length);
  }
  check(res, {
    'inbox answered 200 with a full page': (r) =>
      r.status === 200 && page !== null && Array.isArray(page.items) && page.items.length === LIST_PAGE_SIZE,
    'inbox still has the seeded pending count': () => page !== null && page.pendingCount >= EXPECTED_PENDING,
  });
  if (res.status !== 200 && exec.scenario.iterationInTest < 20) {
    console.warn(`inbox list refused: ${res.status} ${errorText(res)}`);
  }
}

// ---- scenario: bulk approve of 50 (R14.4), one unused batch per iteration -------------------------------

export function bulkApprove(data) {
  const batch = batches[exec.scenario.iterationInTest];
  if (!batch) {
    return; // more iterations than batches cannot happen (iterations = batches.length); belt and braces
  }
  const res = http.post(
    `${BASE_URL}/hr/join-requests/bulk/approve`,
    JSON.stringify({ ids: batch.ids }),
    bearer(tokenFor(data, batch.shopId), batch.shopId, 'POST /hr/join-requests/bulk/approve'),
  );
  answered.add(res.status >= 200 && res.status < 500);
  const result = jsonData(res);
  if (result) {
    bulkOutcomes.add(result.approved || 0, { outcome: 'approved' });
    bulkOutcomes.add((result.skipped || []).length, { outcome: 'skipped' });
    bulkOutcomes.add((result.failed || []).length, { outcome: 'failed' });
  } else {
    bulkOutcomes.add(batch.ids.length, { outcome: res.status >= 500 ? 'server_error' : `refused_${res.status}` });
  }
  check(res, {
    'bulk approve answered 200': (r) => r.status === 200 && result !== null,
    'every id in the batch approved': () => result !== null && result.approved === batch.ids.length,
  });
  if (result && result.approved !== batch.ids.length) {
    const failed = (result.failed || []).slice(0, 3).map((f) => `${f.id}: ${f.message}`).join('; ');
    console.warn(
      `bulk approve batch ${exec.scenario.iterationInTest} (shop ${batch.shopId}): approved ${result.approved}, ` +
        `skipped ${(result.skipped || []).length}, failed ${(result.failed || []).length}${failed ? ` [${failed}]` : ''}`,
    );
  } else if (!result) {
    console.warn(`bulk approve batch ${exec.scenario.iterationInTest}: ${res.status} ${errorText(res)}`);
  }
}

// ---- setup / teardown: manager logins and the server-side histogram ----------------------------------

export function setup() {
  const tokens = {};
  for (const shop of shops) {
    const res = http.post(
      `${BASE_URL}/auth/login`,
      JSON.stringify({ email: shop.ownerEmail, password: fixture.password }),
      { headers: { Accept: 'application/json', 'Content-Type': 'application/json' }, tags: { name: 'POST /auth/login' } },
    );
    const data = jsonData(res);
    if (!data || !data.accessToken) {
      fail(`login for ${shop.ownerEmail} failed: ${res.status} ${errorText(res)}`);
    }
    tokens[shop.shopId] = data.accessToken;
  }
  console.log(
    `${shops.length} shops, ${batches.length} bulk-approve batches of ${BATCH_SIZE}; ` +
      `inbox_list for ${LIST_DURATION} at ${LIST_RATE}/s, then bulk_approve with ${APPROVE_VUS} VU(s)`,
  );
  return { tokens, baseline: scrapeServerHistogram() };
}

export function teardown(data) {
  if (METRICS_URL === 'off') {
    console.log('server-side timers: skipped (METRICS_URL=off)');
    return;
  }
  const end = scrapeServerHistogram();
  if (!data.baseline || !end) {
    fail(`could not read ${SERVER_METRIC} from ${METRICS_URL}; is /actuator/prometheus exposed?`);
  }
  const report = latencyReport(SERVER_TARGETS, data.baseline, end);
  for (const row of report.rows) {
    serverP95.add(row.p95Ms, { target: row.target });
    serverShareWithinTarget.add(row.share, { target: row.target });
    serverRequests.add(row.total, { target: row.target });
  }
  console.log(report.lines.join('\n'));
}

/**
 * Scrapes `SERVER_METRIC` and returns `{ "<method> <uri>": { "<le>": count } }`, summed over
 * `status` and `module` (`lib/server-latency.js` does the parsing).
 */
function scrapeServerHistogram() {
  if (METRICS_URL === 'off') {
    return {};
  }
  const res = http.get(METRICS_URL, { tags: { name: 'GET /actuator/prometheus' } });
  if (res.status !== 200 || typeof res.body !== 'string') {
    console.error(`scrape of ${METRICS_URL} failed: ${res.status}`);
    return null;
  }
  return parseHistogram(res.body, SERVER_METRIC);
}
