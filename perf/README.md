# Load tests

Scripts here are not part of the Maven build. They run against a **throwaway** environment of
`inventory-api` (a local container, or a review stack with its own MongoDB). Never against
production: the seeder creates owner accounts and writes private keys and logins to disk.

## Attendance app punches (`attendance-app-punches.js`)

HR-module spec, R10.9–R10.10 (`.kiro/specs/HR-module/requirements.md`): under 100 punches/s
across shops for 10 minutes, `POST /attendance-app/punches` answers within 500 ms at p95 and the
pairing and history calls within 1 s at p95, **measured server-side**.

| File | What it is |
|------|------------|
| `lib/attendance-protocol.js` | Pure JS copy of the wire protocol (`SKAT-PUNCH-1` payload, `SKAT-REQ-1` envelope, raw→DER ECDSA, UTF-8/Base64/hex); runs under k6 and Node |
| `lib/server-latency.js` | Pure JS reader of the server-side histogram (`inventory_http_latency_bucket_total`): Prometheus text parsing, bucket delta, p95 interpolation, the verdict table; shared by both k6 scripts |
| `check-protocol.mjs` | Node self-check of that library against the Java-generated fixture `core/hr/src/test/resources/attendance-protocol/fixtures/canonical.json` and OpenSSL's strict DER verifier |
| `seed-attendance-app.mjs` | Node 20 seeder: owners, shops, employees, pairings with generated P-256 keys → `attendance-app-fixture.json` |
| `attendance-app-punches.js` | The k6 script |
| `attendance-app-fixture.json` | **Generated, test-only, git-ignored.** Pairing ids + PKCS#8 private keys + owner logins of the seeded accounts |
| `seed-worker-join-requests.mjs` | Node 20 seeder for the worker-app inbox test: owners, shops, WORKER accounts and their pending join requests → `worker-join-requests-fixture.json` |
| `worker-join-requests.js` | The k6 script for the Join requests inbox (see [Worker join requests](#worker-join-requests-worker-join-requestsjs)) |
| `worker-join-requests-fixture.json` | **Generated, test-only, git-ignored.** Owner logins + pending request ids of the seeded accounts |

### Prerequisites

- Node 20+ (global `fetch` and WebCrypto).
- k6 **1.0 or newer**. WebCrypto is the global `crypto` there; the former
  `k6/experimental/webcrypto` module was removed when it graduated and k6 1.x refuses the import.
- A running backend with `/actuator/prometheus` reachable from the k6 host (it is outside the
  `/api/**` auth chain; on a review stack make sure a proxy does not hide it). Without it the
  script still runs but `teardown()` fails the test because the authoritative numbers are missing;
  set `METRICS_URL=off` to run on k6's client-side timers only.
- Self-signed TLS: `NODE_EXTRA_CA_CERTS=/path/ca.pem` for the seeder, `k6 run --insecure-skip-tls-verify`.

### 1. Check the protocol code (no backend needed)

```bash
node perf/check-protocol.mjs
```

Asserts byte equality of both canonical builders with every case in `canonical.json` (the same
fixture the TypeScript `attendance-protocol` package tests against), and that the DER encoder turns
WebCrypto's raw `r||s` signatures into `ECDSA-Sig-Value`s OpenSSL accepts (strict minimal DER, the
same rule Java's `SHA256withECDSA` applies). Run it after touching `lib/attendance-protocol.js`.

### 2. Seed

```bash
BASE_URL=https://localhost:8443/api/v1 node perf/seed-attendance-app.mjs
# knobs: SHOPS=20 EMPLOYEES_PER_SHOP=30 PAIRING_POOL=10 CONCURRENCY=8 OUT=perf/attendance-app-fixture.json
```

Per shop: `POST /auth/signup` (owner) → `POST /shops/register` → `PUT /hr/settings`
`{attendanceCapture: {biometricApp: true}}`. Per worker: `POST /hr/employees` →
`POST /hr/employees/{id}/pairing-codes` → `POST /hr/attendance-app/pairings/claim` → two WebCrypto
P-256 key pairs → `POST /hr/attendance-app/pairings/complete`. That is the request sequence of the
app's live contract test (`stockkart-team-app/src/contract/liveBackend.contract.test.ts`).
It ends with one signed `GET /me` using the fixture's own keys, so a fixture that is written has
already been accepted by the server once.

**Sizing.** `AppRateLimiter` allows 20 punches per pairing per minute (R9.9). 100 punches/s is
6 000/min, so at least 300 pairings are needed; the default 20 × 30 = 600 keeps every pairing at
10/min. Every punch after a worker's first one lands within 5 minutes of the previous and is stored
as `DUPLICATE` (R4.8) — still a full punch (signature check, dedupe, derivation, day write), so the
cost is representative. Seed more shops if you want `STORED` outcomes to dominate.

The pairing pool (`PAIRING_POOL`, one extra worker in each of the first N shops, no pairing) is what
the `pairing` scenario pairs and unpairs over and over; those workers never punch, so replacing
their pairings does not interfere with the punch scenario.

### 3. Run

```bash
k6 run perf/attendance-app-punches.js
# knobs: FIXTURE=perf/attendance-app-fixture.json BASE_URL=… DURATION=10m PUNCH_RATE=100 ME_RATE=10 HISTORY_RATE=5 PAIRING_RATE=30 METRICS_URL=…
# smoke: DURATION=30s PUNCH_RATE=10 k6 run perf/attendance-app-punches.js
```

Scenarios, all `constant-arrival-rate` for `DURATION`:

| Scenario | Rate | What each iteration does |
|----------|------|--------------------------|
| `punches` | 100/s | picks the next pairing round-robin, builds the `SKAT-PUNCH-1` payload (online: `offline=0`, `locationChecked=0`, no metres, `phoneTime` = server-corrected now), signs it with the punch key, wraps the JSON body in a `SKAT-REQ-1` envelope signed by the device key, `POST /attendance-app/punches` |
| `me` | 10/s | signed `GET /attendance-app/me` |
| `history` | 5/s | signed `GET /attendance-app/punches?from=&to=` over the last 31 days |
| `pairing` | 30/min | manager `POST /hr/employees/{id}/pairing-codes` → `claim` → fresh P-256 keys → `complete` → manager `POST /hr/employees/{id}/pairing/unpair` on a pool worker |

Envelope details match `AttendanceAppAuthInterceptor`: `X-Pairing-Id`, `X-Request-Timestamp`
(epoch seconds, corrected by the `X-Server-Time` offset the way the app does), `X-Request-Nonce`
(UUID v4), `X-Request-Signature` (Base64 DER over `SKAT-REQ-1` with the path the server sees,
`/api/v1/...`, the raw query string and the SHA-256 of the exact body bytes). Keys are imported once
per VU and cached; the fixture's PKCS#8 never leaves the k6 process.

A 4xx refusal is an answer R10.9 counts, so `http_req_failed` is configured to count only 5xx and
transport errors (`http.expectedStatuses(200–499)`); the `answered` rate and the `punch_outcomes`
counter (tag `outcome`: `STORED`, `DUPLICATE`, `REPLAYED`, `refused_<status>`, `server_error`) show
what the server said.

### Reading the result

Two latency numbers come out; **the server-side one is the authoritative pass/fail**:

1. **k6 `http_req_duration` per scenario** — client side, includes network, TLS and the k6 host.
   Thresholds: `punches` and `me` p95 < 500 ms, `history` and `pairing` p95 < 1000 ms.
2. **Server-side histogram** — `setup()` and `teardown()` scrape
   `inventory_http_latency_bucket_total{method, uri, le}` from `/actuator/prometheus`; the delta is
   this run's histogram, per Spring path pattern. The meter comes from `HttpServerExtraMetricsFilter`
   (`core/metrics`), which runs at `HIGHEST_PRECEDENCE + 20`, so it times the whole request including
   the signature and nonce checks in the interceptor: as close to "request receipt to response sent"
   as the application can measure. (`http_server_requests_seconds` is switched off in this app by
   `management.observations.enable.[http.server.requests]=false`; `inventory_api_latency_seconds`
   from `@Latency` exists too but times the controller method only, after the interceptor.)
   `teardown()` prints a table and records `server_p95_ms{target}` gauges with the same thresholds:

   ```
   target            method uri                                          requests  p95 ms  share <= target  verdict
   punch             POST /api/v1/hr/attendance-app/punches              60012     212     99.71 %          PASS (p95 <= 500 ms)
   me                GET /api/v1/hr/attendance-app/me                    6001      143     100.00 %         PASS (p95 <= 500 ms)
   history           GET /api/v1/hr/attendance-app/punches               3000      305     100.00 %         PASS (p95 <= 1000 ms)
   pairing_create    POST /api/v1/hr/employees/{id}/pairing-codes        300       …
   ```

   The bucket bounds are 0.05, 0.1, 0.25, **0.5**, **1**, 2.5, 5, 10, 30, 60, 120 s. "p95 ms" is
   interpolated inside the bucket it falls in (what Prometheus `histogram_quantile` does), so it is an
   estimate; "share <= target" is read straight from the `le="0.5"` / `le="1"` bucket and is exact —
   the verdict is `share >= 95 %`, which is precisely "p95 ≤ target". Attach this table to the
   release notes for R10.13-style evidence.

k6 exits non-zero when any threshold fails, including the server-side ones.

### What the run does not cover

- Offline punches (`offline=1`) and Location check on: the seeded shops keep Location check off and
  every punch is online, which is the R10.9 case. Add `distanceMetres`/`accuracyMetres` and flip
  `locationChecked` in `punch()` to load the Allow-and-flag path.
- The rate-limit refusals: with ≥ 300 pairings none should appear; if `punch_outcomes{outcome:refused_429}`
  is non-zero the fixture is too small.
- Multi-instance deployments: the server-side histogram is per instance; scrape each instance or
  read the same meter from Grafana Cloud (`grafana/dashboards/README.md`, HTTP row) instead.

## Worker join requests (`worker-join-requests.js`)

Worker-app spec, R14.4 and R14.6 (`.kiro/specs/worker-app/requirements.md`, design "Performance"):
with 200 pending join requests in a shop, the manager's `GET /hr/join-requests?status=PENDING`
answers within 1 s at p95 and `POST /hr/join-requests/bulk/approve` with 50 ids within 5 s at p95,
**measured server-side**; and the punch bound of the section above still holds after this release.

### Prerequisites

Those of the punch test, plus:

- MongoDB **as a replica set** (the Atlas default; locally `mongod --replSet rs0` + `rs.initiate()`),
  or `MONGO_TRANSACTIONS_ENABLED=false` in the backend's environment. Approval runs in a MongoDB
  transaction (`MongoTx`, design DD12) and a standalone `mongod` refuses transactions.
- A throwaway database. The seeder creates one WORKER **account** per pending request (the partial
  unique index allows one open request per account, R3.5): 200 sign-ups per shop.

### 1. Seed

```bash
BASE_URL=https://localhost:8443/api/v1 node perf/seed-worker-join-requests.mjs
# knobs: SHOPS=3 REQUESTS_PER_SHOP=200 CONCURRENCY=8 OUT=perf/worker-join-requests-fixture.json FRESH=1
```

Per shop: `POST /auth/signup` (owner) → `POST /shops/register`. Per request: `POST /auth/signup`
`{role: WORKER, phone}` → `POST /hr/worker/join-requests {shopId}` with the worker's own token, the
calls the Stockkart Team app makes, so every `hr_join_requests` document is one `WorkerJoinService.create`
wrote (`status PENDING`, frozen snapshot, `expiresAt` null); there is no other kind of request. The sign-ups leave
`source` at its web default on purpose: the 20-an-hour per-IP sign-up limit (R14.1) applies to
`source = worker-app` only, and the account is WORKER because the role says so.

The seeder is idempotent on the pending count: it reads each shop's `pendingCount`, creates only
the missing requests, then pages `GET /hr/join-requests?status=PENDING&size=200` and writes every
pending id to the fixture. After a k6 run has approved the batches, run it again to refill; it reuses
the shops in the fixture (`FRESH=1` makes new ones). Phones are unique within a run so that an
approved worker's new employee never shows up as a "possible match" for a later request (R4.3,
R4.6: bulk approve would skip it).

### 2. Run

```bash
k6 run perf/worker-join-requests.js
# knobs: FIXTURE=perf/worker-join-requests-fixture.json BASE_URL=… LIST_DURATION=2m LIST_RATE=10 APPROVE_VUS=1 MAX_BATCHES=… METRICS_URL=…
# smoke: LIST_DURATION=20s LIST_RATE=2 MAX_BATCHES=2 k6 run perf/worker-join-requests.js
```

Scenarios, one after the other, because approving drains the inbox the list is measured on:

| Scenario | Executor | What each iteration does |
|----------|----------|--------------------------|
| `inbox_list` | `constant-arrival-rate`, `LIST_RATE`/s for `LIST_DURATION` | owner `GET /hr/join-requests?status=PENDING&page=0&size=50` (Bearer + `X-Shop-Id`), shops round-robin; checks a full page of 50 and `pendingCount >= REQUESTS_PER_SHOP` |
| `bulk_approve` | `shared-iterations`, `APPROVE_VUS` VUs, starts 35 s after `inbox_list` ends | owner `POST /hr/join-requests/bulk/approve {ids}` with one unused batch of exactly 50 ids; checks `approved == 50` and counts `bulk_outcomes{outcome: approved | skipped | failed}` |

**No two approvals collide.** At init the fixture's pending ids are cut into batches of 50 per shop
(a remainder under 50 is dropped) and ordered round-robin over the shops (batch 0 of shop A, batch 0
of shop B, …, batch 1 of shop A, …). `bulk_approve` has exactly `batches.length` iterations, and
iteration *i* sends `batches[i]` with *i* = `exec.scenario.iterationInTest`, which k6 numbers
0..n-1 once across every VU of the scenario. Each batch therefore goes out once, from one VU, and
no id is in two requests. The round-robin order also means `APPROVE_VUS > 1` puts concurrent
requests on different shops; two bulk calls on the **same** shop at once would take turns on the
shop's transaction lock (`MongoTx`, AGENTS P8) and the second would be timed waiting for the first.
The default of 1 VU is the R14.4 statement (one manager pressing "Approve selected").

Thresholds, client side: `http_req_duration{scenario:inbox_list}` p95 < 1000 ms,
`http_req_duration{scenario:bulk_approve}` p95 < 5000 ms, `http_req_failed` < 1 %, checks > 99 % in
both scenarios. Server side, authoritative, same mechanism as the punch test: `teardown()` prints
the table and records `server_p95_ms{target:inbox_list}` < 1000 and
`server_p95_ms{target:bulk_approve}` < 5000, read from the `uri` rows `GET /api/v1/hr/join-requests`
and `POST /api/v1/hr/join-requests/bulk/approve`. The histogram has bounds at 1 s and 5 s, so
"share <= target" is exact for both.

### 3. Re-measure the punch path (R14.6)

The Stockkart Team app adds two Bearer-token pieces to the HR module and neither is on the punch path:

- `WorkerCredentialInterceptor` is registered in `app/.../config/CorsConfig.addInterceptors` on
  `WorkerCredentialInterceptor.PATH_PATTERN` = `/api/v1/hr/worker/**` only.
- The device paths, `AttendanceAppAuthInterceptor.DEVICE_PATH_PATTERNS` (`/api/v1/hr/attendance-app/punches`
  among them), are excluded from `AuthenticationInterceptor`, `RbacModuleInterceptor` and
  `PlanExpiryInterceptor` by `CorsConfig.bearerChainExclusions()`. `InterceptorPathMatchingTest`
  (`attendanceAppDevicePathsNeedNoBearerToken`) pins that the Bearer interceptor never touches
  `/api/v1/hr/attendance-app/punches`; the only extra work on that path is the
  `Authorization`-present check inside `AttendanceAppAuthInterceptor` itself (`HR_TWO_CREDENTIALS`).

So the re-measurement is the unchanged punch test, whose `punch` thresholds (`http_req_duration{scenario:punches}`
p95 < 500 and `server_p95_ms{target:punch}` < 500) are the R14.6 assertion:

```bash
BASE_URL=https://localhost:8443/api/v1 node perf/seed-attendance-app.mjs
k6 run perf/attendance-app-punches.js
```

Run it on the same build as the inbox test and attach both server-side tables to the release notes.

### Static checks (no backend needed)

```bash
cd perf && npm run k6:check         # node --check on every script and library
k6 inspect perf/worker-join-requests.js   # k6 parses the script and prints the resolved options
```

`k6 inspect` needs a fixture file to open; `FIXTURE=/path/to/any-fixture.json` points it at one
(the seeder's output, or a hand-written `{"baseUrl": "...", "password": "x", "shops": [{"shopId": "s", "ownerEmail": "o", "pendingIds": [50 ids]}]}`).
