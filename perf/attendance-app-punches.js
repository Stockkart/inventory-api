/**
 * k6 load test for the Attendance app endpoints (HR-module task 16.2, R10.9–R10.10).
 *
 *   k6 run perf/attendance-app-punches.js
 *
 * Needs k6 >= 1.0 (WebCrypto is the global `crypto`; the old `k6/experimental/webcrypto` import
 * was removed when it graduated) and a fixture written by `perf/seed-attendance-app.mjs`:
 * pre-paired workers with their PKCS#8 private keys, plus owner logins for the pairing scenario.
 *
 * Scenarios (all constant arrival rate, all for DURATION, default 10 min):
 *   punches   PUNCH_RATE/s (default 100) signed online punches, rotating over every pairing in
 *             the fixture so each one stays under the 20/min per-pairing limit (R9.9)
 *   me        ME_RATE/s (default 10) GET /me, the home screen
 *   history   HISTORY_RATE/s (default 5) GET /punches?from=&to= over the last 31 days
 *   pairing   PAIRING_RATE per minute (default 30) manager creates a code → claim → fresh P-256
 *             keys → complete → manager unpairs, over the small `pairingPool` of the fixture
 *
 * Two kinds of latency are reported:
 *   - k6's `http_req_duration` per scenario (client side: includes network and TLS). Thresholds:
 *     punches and me p95 < 500 ms, history and pairing p95 < 1000 ms.
 *   - The server's own timer, which is what R10.9 asks for ("from request receipt to response
 *     sent"): `inventory_http_latency_bucket_total{method, uri, le}` from
 *     `HttpServerExtraMetricsFilter` (first in the filter chain, so it covers the signature check
 *     in `AttendanceAppAuthInterceptor` too). `setup()` and `teardown()` scrape
 *     `/actuator/prometheus`, the delta between the two gives the histogram of this run, and the
 *     p95 read from it is the authoritative number, recorded as `server_p95_ms{target}` with the
 *     same thresholds and printed as a table. `http_server_requests_seconds` is disabled in this
 *     app (`management.observations.enable.[http.server.requests]=false`), hence the custom meter.
 *
 * Environment: BASE_URL (overrides the fixture's), FIXTURE (path), DURATION, PUNCH_RATE, ME_RATE,
 * HISTORY_RATE, PAIRING_RATE, METRICS_URL (default BASE_URL origin + /actuator/prometheus; `off`
 * skips the server-side read). Self-signed TLS: `k6 run --insecure-skip-tls-verify`.
 */
import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Counter, Gauge, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';

import {
  HEADERS,
  ROUTES,
  canonicalPunchPayload,
  canonicalRequestEnvelope,
  formatPhoneTime,
  importPrivateKey,
  parseBaseUrl,
  signDerBase64,
  utf8Bytes,
  ECDSA_P256,
  base64Encode,
} from './lib/attendance-protocol.js';

// ---- init: fixture, URLs, options -----------------------------------------------------------------

const FIXTURE_PATH = __ENV.FIXTURE || './attendance-app-fixture.json';
const fixture = JSON.parse(open(FIXTURE_PATH));
if (!fixture || !Array.isArray(fixture.pairings) || fixture.pairings.length === 0) {
  throw new Error(`${FIXTURE_PATH} has no pairings; run perf/seed-attendance-app.mjs first`);
}

const BASE_URL = __ENV.BASE_URL || fixture.baseUrl;
const base = parseBaseUrl(BASE_URL);
const ORIGIN = base.origin;
const BASE_PATH = base.basePath; // e.g. /api/v1 — part of the signed `path`
const METRICS_URL =
  __ENV.METRICS_URL === undefined ? `${ORIGIN}/actuator/prometheus` : __ENV.METRICS_URL;

const DURATION = __ENV.DURATION || '10m';
const PUNCH_RATE = Number(__ENV.PUNCH_RATE || 100);
const ME_RATE = Number(__ENV.ME_RATE || 10);
const HISTORY_RATE = Number(__ENV.HISTORY_RATE || 5);
const PAIRING_RATE = Number(__ENV.PAIRING_RATE || 30); // per minute

const pairings = new SharedArray('pairings', () => fixture.pairings);
const pairingPool = fixture.pairingPool || [];

const SERVER_METRIC = 'inventory_http_latency_bucket_total';
/** The server-side rows to read: Spring path pattern (the `uri` tag) and the R10 target. */
const SERVER_TARGETS = [
  { target: 'punch', method: 'POST', uri: `${BASE_PATH}/hr/attendance-app/punches`, p95Ms: 500 },
  { target: 'me', method: 'GET', uri: `${BASE_PATH}/hr/attendance-app/me`, p95Ms: 500 },
  { target: 'history', method: 'GET', uri: `${BASE_PATH}/hr/attendance-app/punches`, p95Ms: 1000 },
  { target: 'pairing_create', method: 'POST', uri: `${BASE_PATH}/hr/employees/{id}/pairing-codes`, p95Ms: 1000 },
  { target: 'pairing_claim', method: 'POST', uri: `${BASE_PATH}/hr/attendance-app/pairings/claim`, p95Ms: 1000 },
  { target: 'pairing_complete', method: 'POST', uri: `${BASE_PATH}/hr/attendance-app/pairings/complete`, p95Ms: 1000 },
  { target: 'pairing_unpair', method: 'POST', uri: `${BASE_PATH}/hr/employees/{id}/pairing/unpair`, p95Ms: 1000 },
];

const serverP95 = new Gauge('server_p95_ms');
const serverShareWithinTarget = new Gauge('server_share_within_target');
const serverRequests = new Gauge('server_requests');
const punchOutcomes = new Counter('punch_outcomes');
const pairingSteps = new Counter('pairing_steps');
const answered = new Rate('answered'); // 2xx or a 4xx refusal: the server produced an answer

// A refusal (4xx with the API error envelope) is an answer R10.9 counts; only 5xx and transport
// errors are failures for `http_req_failed`.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

const serverThresholds = {};
for (const t of SERVER_TARGETS) {
  serverThresholds[`server_p95_ms{target:${t.target}}`] = [`value<${t.p95Ms}`];
}

export const options = {
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    punches: {
      executor: 'constant-arrival-rate',
      exec: 'punch',
      rate: PUNCH_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(20, Math.ceil(PUNCH_RATE * 0.6)),
      maxVUs: Math.max(100, PUNCH_RATE * 4),
      gracefulStop: '30s',
    },
    me: {
      executor: 'constant-arrival-rate',
      exec: 'me',
      rate: ME_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(5, Math.ceil(ME_RATE * 0.6)),
      maxVUs: Math.max(20, ME_RATE * 4),
      gracefulStop: '30s',
    },
    history: {
      executor: 'constant-arrival-rate',
      exec: 'history',
      rate: HISTORY_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(5, Math.ceil(HISTORY_RATE * 0.8)),
      maxVUs: Math.max(20, HISTORY_RATE * 4),
      gracefulStop: '30s',
    },
    pairing: {
      executor: 'constant-arrival-rate',
      exec: 'pairing',
      rate: PAIRING_RATE,
      timeUnit: '1m',
      duration: DURATION,
      preAllocatedVUs: 4,
      maxVUs: 20,
      gracefulStop: '30s',
    },
  },
  thresholds: Object.assign(
    {
      // Client side (includes network); the server-side numbers below are authoritative.
      'http_req_duration{scenario:punches}': ['p(95)<500'],
      'http_req_duration{scenario:me}': ['p(95)<500'],
      'http_req_duration{scenario:history}': ['p(95)<1000'],
      'http_req_duration{scenario:pairing}': ['p(95)<1000'],
      http_req_failed: ['rate<0.01'],
      answered: ['rate>0.99'],
      'checks{scenario:punches}': ['rate>0.99'],
    },
    serverThresholds,
  ),
};

// ---- per-VU state ---------------------------------------------------------------------------------

/** Imported `CryptoKey`s per pairing index; keys cannot cross the setup/VU boundary, so each VU imports lazily. */
const keyCache = new Map();
/** Server clock minus local clock in whole seconds, from the last `X-Server-Time` (as the app does). */
let serverOffsetSeconds = 0;

async function keysFor(index) {
  let keys = keyCache.get(index);
  if (!keys) {
    const p = pairings[index];
    keys = {
      punch: await importPrivateKey(p.punchPrivateKeyPkcs8),
      device: await importPrivateKey(p.devicePrivateKeyPkcs8),
    };
    keyCache.set(index, keys);
  }
  return keys;
}

function readServerTime(res) {
  const header = res.headers[HEADERS.serverTime] || res.headers[HEADERS.serverTime.toLowerCase()];
  if (header) {
    const serverSeconds = Number(String(header).trim());
    if (Number.isSafeInteger(serverSeconds)) {
      serverOffsetSeconds = serverSeconds - Math.floor(Date.now() / 1000);
    }
  }
}

function serverNowSeconds() {
  return Math.floor(Date.now() / 1000) + serverOffsetSeconds;
}

/** `yyyy-MM-dd` in Asia/Kolkata, `daysAgo` days back. */
function kolkataDate(daysAgo) {
  const ms = Date.now() - daysAgo * 86_400_000 + (5 * 60 + 30) * 60_000;
  return new Date(ms).toISOString().slice(0, 10);
}

/** One signed app call: envelope headers over the exact body bytes, path = the URI the server sees. */
async function signedRequest(deviceKey, pairingId, method, route, query, body, name) {
  const path = `${BASE_PATH}${route}`;
  const timestamp = serverNowSeconds();
  const nonce = crypto.randomUUID().toLowerCase();
  const envelope = await canonicalRequestEnvelope({
    pairingId,
    method,
    path,
    query,
    timestamp,
    nonce,
    body: body === undefined ? undefined : utf8Bytes(body),
  });
  const headers = {
    Accept: 'application/json',
    [HEADERS.pairingId]: pairingId,
    [HEADERS.timestamp]: String(timestamp),
    [HEADERS.nonce]: nonce,
    [HEADERS.signature]: await signDerBase64(deviceKey, envelope),
  };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const url = `${ORIGIN}${path}${query ? `?${query}` : ''}`;
  const res = http.request(method, url, body === undefined ? null : body, {
    headers,
    tags: { name },
  });
  readServerTime(res);
  answered.add(res.status >= 200 && res.status < 500);
  return res;
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

// ---- scenario: punches (R10.9) ----------------------------------------------------------------------

export async function punch() {
  const index = exec.scenario.iterationInTest % pairings.length;
  const p = pairings[index];
  const keys = await keysFor(index);

  const phoneTime = serverNowSeconds(); // online: whole seconds, the server stores its own clock
  const fields = {
    pairingId: p.pairingId,
    shopId: p.shopId,
    employeeId: p.employeeId,
    punchId: crypto.randomUUID().toLowerCase(),
    phoneTime,
    offline: false,
    locationChecked: false, // the seeded shops keep Location check off
    distanceMetres: null,
    accuracyMetres: null,
    mock: false,
  };
  const signature = await signDerBase64(keys.punch, canonicalPunchPayload(fields));
  const body = JSON.stringify({
    pairingId: fields.pairingId,
    shopId: fields.shopId,
    employeeId: fields.employeeId,
    punchId: fields.punchId,
    phoneTime: formatPhoneTime(phoneTime),
    offline: false,
    locationChecked: false,
    distanceMetres: null,
    accuracyMetres: null,
    mock: false,
    signature,
  });

  const res = await signedRequest(
    keys.device,
    p.pairingId,
    'POST',
    ROUTES.punches,
    '',
    body,
    'POST /attendance-app/punches',
  );
  const data = jsonData(res);
  const outcome = data ? data.outcome : res.status >= 500 ? 'server_error' : `refused_${res.status}`;
  punchOutcomes.add(1, { outcome });
  check(res, {
    'punch answered 200 (STORED / DUPLICATE / REPLAYED)': (r) => r.status === 200 && data !== null,
  });
  if (res.status !== 200 && exec.scenario.iterationInTest < 20) {
    console.warn(`punch refused: ${res.status} ${errorText(res)}`);
  }
}

// ---- scenario: me ---------------------------------------------------------------------------------------

export async function me() {
  const index = (exec.scenario.iterationInTest * 7) % pairings.length;
  const p = pairings[index];
  const keys = await keysFor(index);
  const res = await signedRequest(keys.device, p.pairingId, 'GET', ROUTES.me, '', undefined, 'GET /attendance-app/me');
  check(res, { 'me answered 200': (r) => r.status === 200 && jsonData(r) !== null });
}

// ---- scenario: history (R10.10) ---------------------------------------------------------------------------

export async function history() {
  const index = (exec.scenario.iterationInTest * 13) % pairings.length;
  const p = pairings[index];
  const keys = await keysFor(index);
  const query = `from=${kolkataDate(30)}&to=${kolkataDate(0)}`; // 31 days including today
  const res = await signedRequest(
    keys.device,
    p.pairingId,
    'GET',
    ROUTES.punches,
    query,
    undefined,
    'GET /attendance-app/punches',
  );
  check(res, { 'history answered 200': (r) => r.status === 200 && Array.isArray(jsonData(r)) });
}

// ---- scenario: pairing create → claim → complete → unpair (R10.10) ---------------------------------------

function bearer(token, name) {
  return {
    headers: { Accept: 'application/json', 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    tags: { name },
  };
}

export async function pairing(data) {
  if (pairingPool.length === 0) {
    return; // fixture seeded with PAIRING_POOL=0
  }
  const worker = pairingPool[exec.scenario.iterationInTest % pairingPool.length];
  const token = data.tokens[worker.shopId];
  if (!token) {
    fail(`no manager token for shop ${worker.shopId}; setup() login failed`);
  }

  const created = http.post(
    `${BASE_URL}/hr/employees/${worker.employeeId}/pairing-codes`,
    null,
    bearer(token, 'POST /hr/employees/{id}/pairing-codes'),
  );
  pairingSteps.add(1, { step: 'create', status: String(created.status) });
  const code = jsonData(created);
  if (!check(created, { 'pairing code created': (r) => r.status === 200 && code && code.code })) {
    console.warn(`pairing-codes: ${created.status} ${errorText(created)}`);
    return;
  }

  const installationId = crypto.randomUUID();
  const claimed = http.post(
    `${BASE_URL}${ROUTES.claim}`,
    JSON.stringify({
      code: code.code,
      installationId,
      os: 'ANDROID',
      osVersion: '14',
      phoneModel: 'k6 pairing phone',
      appVersion: 'perf',
    }),
    { headers: { Accept: 'application/json', 'Content-Type': 'application/json' }, tags: { name: 'POST /attendance-app/pairings/claim' } },
  );
  pairingSteps.add(1, { step: 'claim', status: String(claimed.status) });
  const claim = jsonData(claimed);
  if (!check(claimed, { 'pairing claimed': (r) => r.status === 200 && claim && claim.claimToken })) {
    console.warn(`claim: ${claimed.status} ${errorText(claimed)}`);
    return;
  }

  // Fresh keys for the "phone", like the app's createKeys(); the private halves are dropped.
  const [punchPair, devicePair] = await Promise.all([
    crypto.subtle.generateKey(ECDSA_P256, true, ['sign', 'verify']),
    crypto.subtle.generateKey(ECDSA_P256, true, ['sign', 'verify']),
  ]);
  const [punchSpki, deviceSpki] = await Promise.all([
    crypto.subtle.exportKey('spki', punchPair.publicKey),
    crypto.subtle.exportKey('spki', devicePair.publicKey),
  ]);

  const completed = http.post(
    `${BASE_URL}${ROUTES.complete}`,
    JSON.stringify({
      claimToken: claim.claimToken,
      installationId,
      punchPublicKey: base64Encode(new Uint8Array(punchSpki)),
      devicePublicKey: base64Encode(new Uint8Array(deviceSpki)),
      consentVersion: claim.consent.version,
    }),
    { headers: { Accept: 'application/json', 'Content-Type': 'application/json' }, tags: { name: 'POST /attendance-app/pairings/complete' } },
  );
  pairingSteps.add(1, { step: 'complete', status: String(completed.status) });
  const bootstrap = jsonData(completed);
  if (!check(completed, { 'pairing completed': (r) => r.status === 200 && bootstrap && bootstrap.pairingId })) {
    console.warn(`complete: ${completed.status} ${errorText(completed)}`);
    return;
  }

  const unpaired = http.post(
    `${BASE_URL}/hr/employees/${worker.employeeId}/pairing/unpair`,
    null,
    bearer(token, 'POST /hr/employees/{id}/pairing/unpair'),
  );
  pairingSteps.add(1, { step: 'unpair', status: String(unpaired.status) });
  check(unpaired, { 'pairing unpaired': (r) => r.status === 200 });
}

// ---- setup / teardown: manager logins and the server-side histogram ----------------------------------

export function setup() {
  const tokens = {};
  const shops = new Set(pairingPool.map((p) => p.shopId));
  for (const shop of fixture.shops || []) {
    if (!shops.has(shop.shopId)) {
      continue;
    }
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
  const lines = [
    '',
    `server-side latency from ${SERVER_METRIC} (delta over this run, filter-level timer)`,
    pad('target', 18) + pad('method uri', 52) + pad('requests', 10) + pad('p95 ms', 10) + pad('share <= target', 17) + 'verdict',
  ];
  for (const t of SERVER_TARGETS) {
    const key = rowKey(t.method, t.uri);
    const delta = subtractBuckets(end[key], data.baseline[key]);
    const total = delta ? delta['+Inf'] || 0 : 0;
    if (total === 0) {
      lines.push(pad(t.target, 18) + pad(`${t.method} ${t.uri}`, 52) + pad('0', 10) + 'no requests in this run');
      continue;
    }
    const p95 = quantileMs(delta, 0.95);
    const share = shareWithin(delta, t.p95Ms / 1000);
    serverP95.add(p95.ms, { target: t.target });
    serverShareWithinTarget.add(share, { target: t.target });
    serverRequests.add(total, { target: t.target });
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
    'behind the verdict is "share <= target >= 95 %", read straight from the le=0.5 / le=1 bucket.',
  );
  console.log(lines.join('\n'));
}

/**
 * Scrapes `SERVER_METRIC` and returns `{ "<method> <uri>": { "<le>": count } }`, summed over
 * `status` and `module`. Tolerates both Prometheus text flavours Micrometer has produced
 * (`{a="b",c="d",}` with the trailing comma, and without).
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
  const out = {};
  const prefix = `${SERVER_METRIC}{`;
  for (const line of res.body.split('\n')) {
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

function parseLabels(text) {
  const labels = {};
  const re = /([A-Za-z_][A-Za-z0-9_]*)="((?:[^"\\]|\\.)*)"/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    labels[m[1]] = m[2].replace(/\\"/g, '"').replace(/\\\\/g, '\\');
  }
  return labels;
}

function rowKey(method, uri) {
  return `${method} ${uri}`;
}

function subtractBuckets(end, start) {
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
function finiteBounds(buckets) {
  return Object.keys(buckets)
    .filter((le) => le !== '+Inf')
    .map((label) => ({ label, value: Number(label) }))
    .filter((b) => Number.isFinite(b.value))
    .sort((a, b) => a.value - b.value);
}

/** Share of requests at or under `seconds`, read from the cumulative bucket with that bound. */
function shareWithin(buckets, seconds) {
  const total = buckets['+Inf'] || 0;
  const bound = finiteBounds(buckets).find((b) => Math.abs(b.value - seconds) < 1e-9);
  if (bound === undefined || total === 0) {
    return NaN;
  }
  return buckets[bound.label] / total;
}

/** Prometheus-style linear interpolation inside the first bucket whose cumulative count reaches the rank. */
function quantileMs(buckets, q) {
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

function pad(text, width) {
  const s = String(text);
  return s.length >= width ? `${s} ` : s + ' '.repeat(width - s.length);
}
