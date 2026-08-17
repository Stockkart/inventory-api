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

Allowed tags: `service`, `env`, `module`, `endpoint`, `method`, `uri` (MVC path pattern only), `status` / `status_class`, `outcome`, `operation`, `provider`, `channel`, `vertical` (`grocery|cafe|sports|medical` only).

Never tag `shopId`, `userId`, `requestId`, document ids, or emails.
