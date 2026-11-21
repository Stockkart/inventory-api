#!/usr/bin/env node
/**
 * Seeds a throwaway inventory-api environment for `perf/attendance-app-punches.js` (HR-module
 * task 16.2, R10.9–R10.10) and writes `perf/attendance-app-fixture.json`.
 *
 * Plain Node 20: global `fetch` and WebCrypto, no dependencies. Everything goes through the
 * backend's own HTTP API, the same request sequence as the app's live contract test
 * (`inventory-platform/apps/attendance/src/contract/liveBackend.contract.test.ts`):
 *
 *   per shop:   POST /auth/signup (owner) → POST /shops/register → PUT /hr/settings
 *               {attendanceCapture: {biometricApp: true}}
 *   per worker: POST /hr/employees → POST /hr/employees/{id}/pairing-codes →
 *               POST /hr/attendance-app/pairings/claim → generate two P-256 key pairs →
 *               POST /hr/attendance-app/pairings/complete
 *
 * The fixture holds the pairing ids with the matching PKCS#8 private keys (Base64) and the owner
 * logins, so the k6 script can sign punches and log in as a manager. It is TEST-ONLY material for
 * accounts this script just created on a throwaway database; it is git-ignored and must never be
 * produced against production.
 *
 * Environment:
 *   BASE_URL            required, e.g. https://localhost:8443/api/v1 (http:// allowed for a local
 *                       container; the real app refuses it, R9.7)
 *   SHOPS               shops to create (default 20)
 *   EMPLOYEES_PER_SHOP  paired workers per shop that the punch scenario uses (default 30)
 *   PAIRING_POOL        extra workers, one per shop for the first N shops, left unpaired for the
 *                       k6 pairing scenario to pair / unpair over and over (default 10)
 *   CONCURRENCY         parallel workers while seeding (default 8; the per-installation code
 *                       lockout counts refusals only, so successes are not limited)
 *   OUT                 fixture path (default perf/attendance-app-fixture.json)
 *   PASSWORD            owner password written to the fixture (default a fixed test value)
 *   NODE_EXTRA_CA_CERTS point at the PEM of a self-signed certificate if the backend uses one
 *
 * Sizing: the punch endpoint allows 20 punches per pairing per minute (R9.9). At 100 punches/s
 * the punch scenario needs at least 300 pairings to stay under that limit; the defaults give 600
 * (10 per pairing per minute). Fewer pairings make the server answer `HR_TOO_MANY_ATTEMPTS`,
 * which is a refusal, still timed, but not what R10.9 is about.
 */
import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  HEADERS,
  ROUTES,
  base64Decode,
  canonicalRequestEnvelope,
  generateKeyPairBase64,
  parseBaseUrl,
  signDerBase64,
  ECDSA_P256,
} from './lib/attendance-protocol.js';

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
const SHOPS = int('SHOPS', 20);
const EMPLOYEES_PER_SHOP = int('EMPLOYEES_PER_SHOP', 30);
const PAIRING_POOL = int('PAIRING_POOL', 10);
const CONCURRENCY = int('CONCURRENCY', 8);
const OUT = process.env.OUT || resolve(here, 'attendance-app-fixture.json');
const PASSWORD = process.env.PASSWORD || 'perf-seed-pass-1';

function int(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < 0) throw new Error(`${name} must be a whole number, got ${raw}`);
  return n;
}

// ---- HTTP ---------------------------------------------------------------------------------------

async function api(method, route, body, token) {
  const headers = { Accept: 'application/json' };
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

/** `yyyy-MM-dd` in Asia/Kolkata, `daysAgo` days before today (join dates must not be in the future). */
function kolkataDate(daysAgo) {
  const ms = Date.now() - daysAgo * 86_400_000 + (5 * 60 + 30) * 60_000;
  return new Date(ms).toISOString().slice(0, 10);
}

function stamp() {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;
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
    phone: `9${String(Math.floor(Math.random() * 1e9)).padStart(9, '0')}`,
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
  await api('PUT', '/hr/settings', { attendanceCapture: { biometricApp: true } }, token);
  return { shopId: shop.shopId, ownerEmail: email, token };
}

async function createEmployee(shop, name) {
  const employee = await api(
    'POST',
    '/hr/employees',
    { name, designation: 'Helper', joinDate: kolkataDate(2) },
    shop.token,
  );
  return employee.id;
}

// ---- worker side ------------------------------------------------------------------------------------

async function pair(shop, employeeId, label) {
  const code = await api('POST', `/hr/employees/${employeeId}/pairing-codes`, undefined, shop.token);
  const installationId = crypto.randomUUID();
  const claim = await api('POST', ROUTES.claim, {
    code: code.code,
    installationId,
    os: 'ANDROID',
    osVersion: '14',
    phoneModel: `k6 load phone ${label}`,
    appVersion: 'perf-seed',
  });
  const [punchKey, deviceKey] = await Promise.all([generateKeyPairBase64(), generateKeyPairBase64()]);
  const bootstrap = await api('POST', ROUTES.complete, {
    claimToken: claim.claimToken,
    installationId,
    punchPublicKey: punchKey.publicKeySpki,
    devicePublicKey: deviceKey.publicKeySpki,
    consentVersion: claim.consent.version,
  });
  if (bootstrap.shopId !== shop.shopId || bootstrap.employeeId !== employeeId) {
    throw new Error(`complete answered another shop/employee for ${label}`);
  }
  return {
    pairingId: bootstrap.pairingId,
    shopId: shop.shopId,
    employeeId,
    installationId,
    punchPrivateKeyPkcs8: punchKey.privateKeyPkcs8,
    devicePrivateKeyPkcs8: deviceKey.privateKeyPkcs8,
  };
}

/** One signed GET /me with the fixture's own key material: proves the keys the k6 script will sign with are accepted. */
async function smoke(pairing) {
  const deviceKey = await crypto.subtle.importKey(
    'pkcs8',
    base64Decode(pairing.devicePrivateKeyPkcs8),
    ECDSA_P256,
    false,
    ['sign'],
  );
  const path = `${base.basePath}${ROUTES.me}`;
  const timestamp = Math.floor(Date.now() / 1000);
  const nonce = crypto.randomUUID().toLowerCase();
  const envelope = await canonicalRequestEnvelope({
    pairingId: pairing.pairingId,
    method: 'GET',
    path,
    query: '',
    timestamp,
    nonce,
  });
  const response = await fetch(`${base.origin}${path}`, {
    headers: {
      Accept: 'application/json',
      [HEADERS.pairingId]: pairing.pairingId,
      [HEADERS.timestamp]: String(timestamp),
      [HEADERS.nonce]: nonce,
      [HEADERS.signature]: await signDerBase64(deviceKey, envelope),
    },
  });
  const payload = await response.json().catch(() => ({}));
  if (response.status !== 200 || payload.success !== true || payload.data?.canPunch !== true) {
    throw new Error(`smoke GET /me failed: ${response.status} ${JSON.stringify(payload).slice(0, 300)}`);
  }
  return payload.data;
}

// ---- main -----------------------------------------------------------------------------------------------

const startedAt = Date.now();
console.log(`seeding ${SHOPS} shops × ${EMPLOYEES_PER_SHOP} paired workers + ${PAIRING_POOL} pool workers at ${BASE_URL}`);

const shops = await mapLimit(Array.from({ length: SHOPS }, (_, i) => i), CONCURRENCY, createShop);
console.log(`shops ready (${shops.length})`);

const jobs = [];
shops.forEach((shop, s) => {
  for (let e = 0; e < EMPLOYEES_PER_SHOP; e++) jobs.push({ shop, s, e, pool: false });
  if (s < PAIRING_POOL) jobs.push({ shop, s, e: EMPLOYEES_PER_SHOP, pool: true });
});

let done = 0;
const results = await mapLimit(jobs, CONCURRENCY, async (job) => {
  const label = `${job.s}-${job.e}`;
  const employeeId = await createEmployee(job.shop, `Perf Worker ${label}`);
  const result = job.pool
    ? { pool: true, shopId: job.shop.shopId, employeeId }
    : { pool: false, pairing: await pair(job.shop, employeeId, label) };
  done++;
  if (done % 100 === 0) console.log(`  ${done}/${jobs.length} workers`);
  return result;
});

const pairings = results.filter((r) => !r.pool).map((r) => r.pairing);
const pairingPool = results.filter((r) => r.pool).map((r) => ({ shopId: r.shopId, employeeId: r.employeeId }));

if (pairings.length > 0) {
  const me = await smoke(pairings[0]);
  console.log(`smoke GET /me ok for ${me.employeeName} (nextDirection ${me.nextDirection})`);
}

const fixture = {
  _warning:
    'TEST-ONLY. Private keys and owner logins for throwaway load-test accounts created by perf/seed-attendance-app.mjs. ' +
    'Never generate against production, never commit, delete after the run.',
  generatedAt: new Date().toISOString(),
  baseUrl: BASE_URL,
  password: PASSWORD,
  shops: shops.map(({ shopId, ownerEmail }) => ({ shopId, ownerEmail })),
  pairings,
  pairingPool,
};
writeFileSync(OUT, `${JSON.stringify(fixture, null, 2)}\n`);
console.log(
  `wrote ${OUT}: ${pairings.length} pairings, ${pairingPool.length} pool workers, ${shops.length} shops ` +
    `in ${((Date.now() - startedAt) / 1000).toFixed(1)} s`,
);
if (pairings.length < 300) {
  console.warn(
    `warning: ${pairings.length} pairings cannot absorb 100 punches/s under the 20/min per-pairing limit; raise SHOPS or EMPLOYEES_PER_SHOP`,
  );
}
