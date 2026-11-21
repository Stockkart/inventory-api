# Grafana Cloud dashboards

Import these JSON files into Grafana Cloud (Mimir/Prometheus). Dashboards only query; the API already pushes OTLP metrics every 60s.

Datasource at import: **`grafanacloud-myntrackofficial-prom`**. Refresh is **1m**. Filter every panel with `{service="inventory-api", env="$env"}`.

## Import order

1. `01-infrastructure.json` — CPU, JVM heap/GC/threads, Tomcat, executors, Mongo pool, optional disk
2. `02-system-health.json` — uptime, ready time, logback errors, global HTTP 5xx, Mongo errors
3. `03-module.json` — **the module dashboard** (HTTP + business for one `module` dropdown)
4. `04-external.json` — OCR by provider, Razorpay, notification send failures

When Grafana prompts for the Prometheus datasource, bind **`grafanacloud-myntrackofficial-prom`**.

## Module dropdown (`03-module.json`)

The `module` dropdown is a **fixed list** (not `label_values`). Grafana Cloud Mimir often rejects `{__name__=~"inventory_.+"}` as too wide, so the variable would fail and stick on the default `product` even after you load analytics/plan/accounting.

Re-import `03-module.json` (or edit the dashboard variable in Grafana) after pulling this change. Then pick `analytics`, `plan`, `accounting`, `user` in the dropdown — the HTTP/business rows filter to that label. Empty charts mean that module has no series yet in Mimir (API not rebuilt, or no request with that `module` tag in the last 60s OTLP step).

**Shops** in the UI call `/api/v1/shops` (`ShopController`, `module=product`). Auth / CRM / invites are `user`. There is no `shops` module name.

| Dropdown | HTTP row | Business row |
|----------|----------|----------------|
| `user` | Auth / shop / CRM / invite controllers | auth, shops, customers, vendors, invitations, join requests, RBAC |
| `product` | Scan / checkout / inventory / invoices | carts, GMV, orders, refunds, corrections, purchases, quotations, … |
| `ocr` | empty (OCR has no REST controller) | parse timer, items, success/error by `provider` |
| `plan` | Plan / payment / webhook | assigned, checkout/verify, webhooks |
| `analytics` | charts + MIS | `inventory_analytics_queries_total`, `inventory_mis_reports_total` |
| `notifications` | empty | enqueue / send by `channel` |
| `cafe` | empty (sales HTTP stays under `product`) | token issue / complete |

Business panels **repeat** over `label_values({__name__=~"inventory_$module_.+", module="$module"}, __name__)`. Adding a new `inventory_<module>_*` meter does not require a new JSON file.

HTTP request count is recorded on **`inventory_api_requests_total`** (same Counter as the AOP meter that already graphs), with extra `uri` and `status` tags. Filter HTTP rows with `uri=~".+"`.

| Meter | PromQL |
|-------|--------|
| `inventory_api_requests_total{uri=~".+"}` | requests / 5 min = `increase(...[5m])`; table RPS = that / 300 |
| `inventory_http_duration_seconds_total` | average = duration increase / request increase |
| `inventory_http_latency_bucket_total` (`le`) | p95/p99 via `histogram_quantile` + `sum by (le, method, uri)` |

Do not graph HTTP as `rate()` with unit `reqps` and 0 decimals — a few requests per minute is ~0.03/s and Grafana rounds it to **0**. Re-import `03-module.json` after rebuilding the API.

### Search row (`module=product`)

A collapsed **Search** row sits between HTTP and Business. It only has data when the dropdown is `product`. All meters are Counters with `module="product"` and a `surface` tag (`product-search`, `scan-sell`, `legacy-get`); none carry shop, user or search values.

| Meter | What it is | PromQL |
|-------|------------|--------|
| `inventory_product_search_requests_total{surface, outcome, mode, text, filters}` | one per search; `outcome` = `ok`, `timeout`, `regex_rejected`, `validation_error`, `error` | searches / 5 min = `sum by (surface, outcome) (increase(...[5m]))` |
| `inventory_product_search_latency_bucket_total{le}` | cumulative buckets in **ms** (50, 100, 250, 500, 1000, 2500, 5000, +Inf) | p95 = `histogram_quantile(0.95, sum by (le) (increase(...[5m])))` |
| `inventory_product_search_duration_ms_total` | sum of wall time | average = this / `requests_total{outcome="ok"}` |
| `inventory_product_search_db_ms_total` | sum of time inside Mongo | same division; a gap to total = enrichment cost |
| `inventory_product_search_matched_total` | sum of matched lots | average matches per search = this / ok requests |
| `inventory_product_search_facets_total{facet}` | facets requested | by field |
| `inventory_product_search_slow_total` | searches over 500 ms (each also logs `[search-slow]` with the request shape, enough to reproduce it and run `explain` by hand) | / 5 min |

Documents examined per search is not measured by the app (it used to re-run searches with `explain`, doubling the database work when searches were slow). On a dedicated Atlas tier (M10+) the Query Profiler records it for slow operations. Index use is checked in `InventorySearchEngineIT.explainShowsIndexScanFirst`.

Suggested alerts: **p95 latency > 1 s for 10 min** and **more than 5 `outcome="timeout"` in 5 min** (both on `env="prod"`).

### Attendance app panels (`module=hr`)

Three panels at the bottom of `03-module.json` (ids 200–202) cover the HR Attendance app (spec `.kiro/specs/HR-module`, R10.7). They are the only panels that use the **`shop`** dropdown (`label_values(inventory_hr_app_punches_total{service="inventory-api", env="$env"}, shop)`, multi-select, default All); the other rows ignore it. Panels are plain (not repeated) and have data regardless of the `module` dropdown. Each panel's description links the metric rows in [`docs/metrics.md`](../../docs/metrics.md#hr).

| Panel | PromQL | What to look for |
|-------|--------|------------------|
| Attendance app punches per result | A: `sum by (result) (rate(inventory_hr_app_punches_total{shop=~"$shop"}[5m]))` (stacked, left axis). B: `(sum by (shop) (rate(…{result="signature_failed"}[1h])) or sum by (shop) (rate(…[1h])) * 0) / sum by (shop) (rate(…[1h]))` — the R10.4 signature failure rate per shop over a rolling hour, dashed red on the right axis (`percentunit`), with a **threshold line at 1 %** | one `result` label per attempt (`accepted`, `flagged`, `duplicate`, `replayed`, `off`, `not_paired`, `not_employed`, `on_leave`, `locked`, `outside_shop`, `location_unclear`, `fake_location`, `offline_accepted`, `offline_too_late`, `offline_clock_wrong`, `signature_failed`, `rate_limited`, `invalid`, `error`). A dashed line above the 1 % threshold is the condition `AppPunchHealthService` alerts on (it additionally needs ≥ 20 attempts in the window, which the graph does not encode — read the attempt rate off query A) |
| Attendance app offline backlog | A: `sum(increase(inventory_hr_app_offline_backlog_total{shop=~"$shop"}[1h]))` (all shops, thick line). B: `sum by (shop) (increase(…[1h]))` (one series per shop). Legend is a **table** sorted by Last with Last / Max / Mean per shop | accepted offline punches that arrived more than 1 h after their phone time; the legend table is the per-shop breakdown, so the shop holding punches right now is the top row |
| Attendance app pairing failures per reason | `sum by (reason) (rate(inventory_hr_app_pairings_total{event="failed",shop=~"$shop"}[5m]))` | `code_expired`, `code_used`, `code_cancelled`, `code_wrong`, `app_off`, `employee_exited`, `abandoned`, `locked` |

The `or … * 0` in query B of the first panel keeps a shop with zero signature failures on the graph at 0 % instead of dropping its series.

Every expression also carries `service="inventory-api", env="$env"` like the rest of the file. Metric names and tag values are the constants in `HrMetricsConstants` (`APP_PUNCHES_TOTAL`, `APP_PAIRINGS_TOTAL`, `APP_OFFLINE_BACKLOG_TOTAL`); rows for them are in `docs/metrics.md`. `DashboardJsonTest` (`app/src/test/.../observability`) pins the panel set to the pre-existing ids plus exactly 200–202, query A of each, the 1 % threshold override, the per-shop query and the doc links; pre-existing panels must stay byte-identical to `HEAD`.

## Explore smoke queries

After generating traffic (login, checkout, OCR, plan checkout, MIS, reminder, credit charge, journal, GSTR, cafe token):

```promql
{service="inventory-api"}
```

```promql
count by (module) (inventory_api_status_total{service="inventory-api"})
```

Prefer that over `label_values({__name__=~"inventory_.+"}, module)` in Explore — the regex matcher is expensive on Grafana Cloud and is what emptied the dashboard dropdown.

```promql
{__name__=~"inventory_user_.+|inventory_product_.+|inventory_ocr_.+|inventory_plan_.+|inventory_analytics_.+|inventory_mis_.+|inventory_reminders_.+|inventory_notifications_.+|inventory_documents_.+|inventory_credit_.+|inventory_accounting_.+|inventory_taxation_.+|inventory_cafe_.+|inventory_resource_.+"}
```

Expect every HTTP `module` label and the business names above within ~2 minutes (OTLP step is 60s). Confirm no `shopId` / `userId` / `requestId` labels.

```promql
{__name__=~"inventory_http_.*", service="inventory-api"}
```

```promql
topk(20, sum by (method, uri) (increase(inventory_api_requests_total{service="inventory-api", uri=~".+"}[5m])))
```

```promql
histogram_quantile(0.95, sum by (le, method, uri) (increase(inventory_http_latency_bucket_total{service="inventory-api"}[5m])))
```

## Cardinality

Allowed tags: `service`, `env`, `module`, `endpoint`, `method`, `uri` (MVC path pattern only), `status` / `status_class`, `outcome`, `operation`, `provider`, `channel`, `vertical` (`grocery|cafe|sports|medical` only). The three HR Attendance app counters (`inventory_hr_app_*`) additionally carry `shop` (the shop id), `result`, `event` and `reason`, because R10.1–R10.3 ask for per-shop counts; no other metric may carry a shop id.

Never tag `userId`, `requestId`, document ids, or emails; never tag `shopId` outside the three counters above.
