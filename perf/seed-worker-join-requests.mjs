#!/usr/bin/env node
/**
 * Seeds a throwaway inventory-api environment for `perf/worker-join-requests.js` (worker-app task
 * 7.5, R14.4) and writes `perf/worker-join-requests-fixture.json`.
 *
 * Plain Node 20: global `fetch`, no dependencies. Everything goes through the backend's own HTTP
 * API, the same calls the Stockkart Team app and the manager web make, so every document is one the
 * service wrote itself (snapshot, partial unique index, counters, notifications included):
 *
 *   per shop:    POST /auth/signup (owner) → POST /shops/register
 *   per request: POST /auth/signup {role: WORKER, phone} → POST /hr/worker/join-requests {shopId}
 *   at the end:  GET /hr/join-requests?status=PENDING&page=&size=200 as the owner, to list every
 *                pending id the k6 script may approve
 *
 * Idempotent on the pending count: re-running tops each shop up to REQUESTS_PER_SHOP pending
 * requests (a k6 run approves them, so a second run refills), reuses the shops of an existing
 * fixture and rewrites the id list from the server. `FRESH=1` ignores the fixture and makes new
 * shops.
 *
 * One pending request per account (partial unique index, R3.5) means one WORKER account per
 * request: 200 sign-ups per shop. The sign-ups leave `source` at its web default on purpose: the
 * 20-an-hour per-IP limit (R14.1) applies to `source = worker-app` only, and the account is still
 * WORKER because the role says so. The join limiter (5 a day per account) is never reached.
 *
 * The fixture holds the owner logins and the pending request ids. It is TEST-ONLY material for
 * accounts this script just created on a throwaway database; it is git-ignored and must never be
 * produced against production.
 *
 * Environment:
 *   BASE_URL           required, e.g. https://localhost:8443/api/v1 (http:// allowed for a local
 *                      container)
 *   SHOPS              shops to create when no fixture is reused (default 3)
 *   REQUESTS_PER_SHOP  pending requests each shop should have when the script ends (default 200,
 *                      the R14.4 figure; the inbox page is at most 200 rows)
 *   CONCURRENCY        parallel sign-up + request pairs (default 8)
 *   OUT                fixture path (default perf/worker-join-requests-fixture.json)
 *   PASSWORD           password of every account written to the fixture (default a fixed test value)
 *   FRESH              set to 1 to ignore an existing fixture and create new shops
 *   NODE_EXTRA_CA_CERTS point at the PEM of a self-signed certificate if the backend uses one
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { parseBaseUrl } from './lib/attendance-protocol.js';

const here = dirname(fileURLToPath(import.meta.url));

const BASE_URL = process.env.BASE_URL;
if (!BASE_URL) {
  console.error('BASE_URL is required, e.g. BASE_URL=https://localhost:8443/api/v1');
  process.exit(2);
}
const base = parseBaseUrl(BASE_URL);
if (base.scheme !== 'https') {
  console.warn('warning: BASE_URL is plain http; fine for a local container, never for a shared host');
}
const SHOPS = int('SHOPS', 3);
const REQUESTS_PER_SHOP = int('REQUESTS_PER_SHOP', 200);
const CONCURRENCY = int('CONCURRENCY', 8);
const OUT = process.env.OUT || resolve(here, 'worker-join-requests-fixture.json');
const PASSWORD = process.env.PASSWORD || 'perf-seed-pass-1';
const FRESH = process.env.FRESH === '1';

/** The largest inbox page (`JoinRequestInboxValidator.MAX_PAGE_SIZE`). */
const PAGE_SIZE = 200;

function int(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < 0) throw new Error(`${name} must be a whole number, got ${raw}`);
  return n;
}

// ---- HTTP ---------------------------------------------------------------------------------------

async function api(method, route, body, token, extraHeaders) {
  const headers = { Accept: 'application/json', ...(extraHeaders || {}) };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(`${BASE_URL}${route}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let payload;
  try {
    payload = JSON.parse(text);
  } catch {
    throw new Error(`${method} ${route} → ${response.status}: not JSON: ${text.slice(0, 200)}`);
  }
  if (!response.ok || payload.success !== true) {
    throw new Error(`${method} ${route} → ${response.status}: ${JSON.stringify(payload).slice(0, 400)}`);
  }
  return payload.data;
}

function stamp() {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;
}

/**
 * A 10-digit mobile that is unique within this run: `9` + nine digits taken from the run's start
 * time plus a counter. Unique phones matter because approval creates an employee with the
 * snapshot's phone, and a later request with the same phone would show a "possible match" and be
 * skipped by bulk approve (R4.3, R4.6), which is not what the load test measures.
 */
const RUN_BASE = Date.now() % 1_000_000_000;
let phoneCounter = 0;
function nextPhone() {
  const n = (RUN_BASE + phoneCounter++) % 1_000_000_000;
  return `9${String(n).padStart(9, '0')}`;
}

async function mapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  async function worker() {
    for (;;) {
      const i = next++;
      if (i >= items.length) return;
      out[i] = await fn(items[i], i);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return out;
}

// ---- manager side ---------------------------------------------------------------------------------

async function createShop(index) {
  const s = `${stamp()}${index}`;
  const email = `perf-owner-${s}@example.invalid`;
  const signup = await api('POST', '/auth/signup', {
    name: `Perf Owner ${s}`,
    email,
    password: PASSWORD,
    phone: nextPhone(),
    role: 'OWNER',
  });
  const token = signup.accessToken;
  const shop = await api(
    'POST',
    '/shops/register',
    {
      name: `Perf Shop ${s}`,
      businessId: `PERF-${s}`,
      location: { primaryAddress: '1 Load Test Road', state: 'KA', city: 'Bengaluru', pin: '560001' },
      contactEmail: `perf-shop-${s}@example.invalid`,
      contactPhone: '9876500000',
      shopType: 'RETAILER',
      verticalId: 'grocery',
    },
    token,
  );
  return { shopId: shop.shopId, ownerEmail: email, token };
}

async function loginShop(shop) {
  const login = await api('POST', '/auth/login', { email: shop.ownerEmail, password: PASSWORD });
  return { shopId: shop.shopId, ownerEmail: shop.ownerEmail, token: login.accessToken };
}

/** One inbox page as the owner; `X-Shop-Id` names the shop explicitly, as the web does. */
async function inboxPage(shop, page, size) {
  return api(
    'GET',
    `/hr/join-requests?status=PENDING&page=${page}&size=${size}`,
    undefined,
    shop.token,
    { 'X-Shop-Id': shop.shopId },
  );
}

async function pendingCount(shop) {
  const first = await inboxPage(shop, 0, 1);
  return first.pendingCount;
}

async function pendingIds(shop) {
  const ids = [];
  for (let page = 0; ; page++) {
    const data = await inboxPage(shop, page, PAGE_SIZE);
    for (const row of data.items) ids.push(row.id);
    if (page + 1 >= data.totalPages || data.items.length === 0) break;
  }
  return ids;
}

// ---- worker side ------------------------------------------------------------------------------------

/** Sign up one WORKER account and have it ask to join `shop`; returns the request id and the login. */
async function requestToJoin(shop, label) {
  const s = `${stamp()}${label}`;
  const email = `perf-worker-${s}@example.invalid`;
  const signup = await api('POST', '/auth/signup', {
    name: `Perf Worker ${label}`,
    email,
    password: PASSWORD,
    phone: nextPhone(),
    role: 'WORKER',
  });
  const request = await api('POST', '/hr/worker/join-requests', { shopId: shop.shopId }, signup.accessToken);
  if (request.status !== 'PENDING') {
    throw new Error(`join request for ${email} is ${request.status}, expected PENDING`);
  }
  return { requestId: request.id, email };
}

// ---- main -----------------------------------------------------------------------------------------------

const startedAt = Date.now();

let shops;
let existing = null;
if (!FRESH && existsSync(OUT)) {
  existing = JSON.parse(readFileSync(OUT, 'utf8'));
  if (existing.baseUrl !== BASE_URL) {
    console.warn(`warning: ${OUT} was seeded against ${existing.baseUrl}, reusing its shops at ${BASE_URL} anyway`);
  }
  shops = await mapLimit(existing.shops, CONCURRENCY, loginShop);
  console.log(`reusing ${shops.length} shops from ${OUT} (FRESH=1 to start over)`);
} else {
  console.log(`creating ${SHOPS} shops at ${BASE_URL}`);
  shops = await mapLimit(Array.from({ length: SHOPS }, (_, i) => i), CONCURRENCY, createShop);
  console.log(`shops ready (${shops.length})`);
}

const jobs = [];
for (const [s, shop] of shops.entries()) {
  const have = await pendingCount(shop);
  const missing = Math.max(0, REQUESTS_PER_SHOP - have);
  console.log(`shop ${shop.shopId}: ${have} pending, creating ${missing}`);
  for (let r = 0; r < missing; r++) jobs.push({ shop, label: `${s}-${have + r}` });
}

let done = 0;
const created = await mapLimit(jobs, CONCURRENCY, async (job) => {
  const result = await requestToJoin(job.shop, job.label);
  done++;
  if (done % 100 === 0) console.log(`  ${done}/${jobs.length} requests`);
  return { shopId: job.shop.shopId, ...result };
});

const fixtureShops = [];
for (const shop of shops) {
  const ids = await pendingIds(shop);
  if (ids.length < REQUESTS_PER_SHOP) {
    console.warn(`warning: shop ${shop.shopId} lists ${ids.length} pending requests, wanted ${REQUESTS_PER_SHOP}`);
  }
  fixtureShops.push({ shopId: shop.shopId, ownerEmail: shop.ownerEmail, pendingIds: ids });
}

const previousWorkers = existing ? existing.workers || [] : [];
const fixture = {
  _warning:
    'TEST-ONLY. Owner logins and pending join-request ids of throwaway load-test accounts created by ' +
    'perf/seed-worker-join-requests.mjs. Never generate against production, never commit, delete after the run.',
  generatedAt: new Date().toISOString(),
  baseUrl: BASE_URL,
  password: PASSWORD,
  requestsPerShop: REQUESTS_PER_SHOP,
  shops: fixtureShops,
  /** Every WORKER login this seeder made (all share `password`), for clean-up or a manual sign-in. */
  workers: previousWorkers.concat(created.map(({ shopId, email, requestId }) => ({ shopId, email, requestId }))),
};
writeFileSync(OUT, `${JSON.stringify(fixture, null, 2)}\n`);

const totalPending = fixtureShops.reduce((n, s) => n + s.pendingIds.length, 0);
console.log(
  `wrote ${OUT}: ${shops.length} shops, ${totalPending} pending requests (${created.length} new), ` +
    `${Math.floor(totalPending / 50)} bulk-approve batches of 50, in ${((Date.now() - startedAt) / 1000).toFixed(1)} s`,
);
