# Stock Kart – Inventory API

Backend API for the Stock Kart inventory management system. A Spring Boot–based multi-module application with MongoDB, supporting inventory, billing, plans, OCR invoice parsing, and extensible plugins.

---

## Table of Contents

- [Project Overview](#project-overview)
- [Local Setup with Docker](#local-setup-with-docker)
- [Deploying](docs/deploying.md) — staging, production, branch deploys, rollback
- [Multimodule Structure](#multimodule-structure)
- [Project Structure](#project-structure)
- [Contributing Guidelines](#contributing-guidelines)

---

## Project Overview

Stock Kart Inventory API provides:

- **Inventory & product management** – Lots, units, pricing, stock tracking
- **Checkout & sales** – Cart, billing, tax, schemes, refunds
- **Plans & usage** – Subscription plans, usage tracking, payment webhooks
- **OCR invoice parsing** – Parse invoices via AWS Textract, OpenAI, or Gemini
- **Documents & invoicing** – Invoice generation, PDF handling
- **User & shop management** – Multi-shop, roles, invitations
- **Reminders** – Low-stock alerts, SSE events, expiry reminders
- **Notifications** – Email (Resend), WhatsApp-ready; signup/login emails
- **Analytics** – Customer analytics, dashboards
- **Plugin engine** – Extensible domain plugins (medical, sports, …)
- **Vertical schemas** – DB-backed `vertical_schemas`; schema-driven validation and UI metadata

**Tech stack:** Java 21, Spring Boot 3.3, MongoDB, Maven, MapStruct, Lombok

---

## Local Setup with Docker

### Prerequisites

- **Docker** and **Docker Compose**
- **Java 21** (for local Maven builds)
- **Maven 3.9+** (or use `./mvnw` if available)
- **MongoDB** – local or hosted (e.g. MongoDB Atlas)

### 1. Clone and configure environment

```bash
git clone <repository-url>
cd inventory-api
```

Copy the example environment file and edit it:

```bash
cp .env.example .env
```

Edit `.env` and set at least:

| Variable | Description | Example |
|----------|-------------|---------|
| `DB_URI` | MongoDB connection URI | `mongodb://localhost:27017/inventory` or Atlas URI |
| `CLIENT_URL` | Frontend origin for CORS | `http://localhost:3000` |
| `OCR_PROVIDER` | OCR provider: `aws-textract`, `chatgpt`, `gemini` | `chatgpt` |
| `OPENAI_API_KEY` | Required when `OCR_PROVIDER=chatgpt` | `sk-...` |
| `GEMINI_API_KEY` | Required when `OCR_PROVIDER=gemini` | `...` |
| `UPLOAD_TOKEN_EXPIRY_MINUTES` | Upload token validity | `15` |

Optional (for AWS Textract):

- `AWS_ACCESS_KEY`, `AWS_SECRET_ACCESS`, `AWS_REGION`

### 2. Run with Docker Compose

Build and start the app (and image-preprocess if configured):

```bash
docker compose up --build
```

The API will be available at **http://localhost:8080**.

**Note:** If the `image-preprocess` service fails (e.g. missing `image_preprocess/Dockerfile`):

- Set `OCR_PREPROCESS_MODE=none` in `.env`, and in `docker-compose.yml` comment out:
  - the entire `image-preprocess` service block
  - the `depends_on` block under the `app` service
- Otherwise, add the image preprocessing service and Dockerfile if you need OCR preprocessing.

### 3. Run MongoDB locally (optional)

If you prefer a local MongoDB instead of Atlas, uncomment the `mongodb` service in `docker-compose.yml` and set:

```
DB_URI=mongodb://${DB_USERNAME}:${DB_PASSWORD}@mongodb:27017/inventory?authSource=admin
```

### 4. Run without Docker (local dev)

For faster feedback during development:

```bash
# Ensure MongoDB is running (local or Atlas)
cp .env.example .env
# Edit .env with your values

# Build all modules
mvn clean install -DskipTests

# Run the app
mvn -pl app spring-boot:run
```

The app loads `.env` from the project root and exposes:

- **API:** http://localhost:8080  
- **Health:** http://localhost:8080/actuator/health  
- **Prometheus:** http://localhost:8080/actuator/prometheus  

### Grafana Cloud (optional)

The API **pushes** metrics (OTLP) and INFO logs (Loki HTTP) to Grafana Cloud. Nothing extra runs on the app server. Leave the `GRAFANA_CLOUD_*` vars empty locally.

On **DigitalOcean App Platform** set:

| Variable | Description |
|----------|-------------|
| `SPRING_PROFILES_ACTIVE` | `prod` (INFO logs; sets `env=prod` on metrics/logs) |
| `GRAFANA_CLOUD_OTLP_URL` | OTLP metrics URL from Grafana Cloud (…`/otlp/v1/metrics`) |
| `GRAFANA_CLOUD_OTLP_USER` | Prometheus / OTLP instance user id |
| `GRAFANA_CLOUD_LOKI_URL` | Loki push URL (`…/loki/api/v1/push`) |
| `GRAFANA_CLOUD_LOKI_USER` | Loki instance user id |
| `GRAFANA_CLOUD_API_TOKEN` | Access policy with metrics write + logs write (encrypted) |

In Grafana Explore (Loki): `{service="inventory-api"} | json | shopId="..."` or `requestId="..."`. Dashboard refresh 60s. Responses include `X-Request-Id`.

---

## Multimodule Structure

The project is a Maven multi-module build:

```
inventory-api/
├── app/                 # Main Spring Boot application (entry point)
├── core/                # Core business modules
│   ├── common/          # Shared utilities, exceptions, constants
│   ├── product/         # Inventory, checkout, refunds, lots
│   ├── plan/            # Subscription plans, usage, webhooks
│   ├── pricing/         # Pricing abstraction (AOP handlers)
│   ├── user/            # Users, shops, customers, vendors
│   ├── documentservice/ # Invoice generation, documents
│   ├── ocr/             # OCR providers (Textract, OpenAI, Gemini)
│   ├── reminders/       # Events, reminders, low-stock SSE
│   ├── notifications/   # Email (Resend), WhatsApp; async queue
│   ├── analytics/       # Customer analytics, reports
│   └── taxation/        # Tax calculations
├── plugins/             # Domain plugins
│   └── medical/         # Medical/healthcare plugin
└── pluginengine/        # Plugin loading and discovery
```

### Module roles

| Module | Purpose |
|--------|---------|
| **app** | Bootstraps Spring Boot; wires core + plugins; configures CORS, security, MongoDB |
| **core/common** | Shared exceptions, constants, base DTOs, validation |
| **core/product** | Inventory, purchases, cart, checkout, refunds, lots, business types |
| **core/plan** | Plans, usage, payment webhooks, trial logic |
| **core/pricing** | Pricing read/write AOP and handlers |
| **core/user** | Users, shops, customers, vendors, invitations |
| **core/documentservice** | Invoice generation and document handling |
| **core/ocr** | OCR for invoice parsing (AWS, OpenAI, Gemini) |
| **core/reminders** | Events, reminders, low-stock SSE alerts |
| **core/notifications** | Email (Resend), WhatsApp; async queue with retry |
| **core/analytics** | Customer analytics and reporting |
| **core/taxation** | Tax calculation logic |
| **pluginengine** | Plugin discovery and registration |
| **plugins/medical** | Medical-domain extensions |
| **plugins/sports** | Sports-domain extensions |

### Documentation

- [Vertical Plugin Architecture v4.9](docs/VERTICAL_PLUGIN_ARCHITECTURE.md) — architecture, **implementation status**, phases 1–7 roadmap
- [Vertical Plugin Architecture (HTML)](docs/VERTICAL_PLUGIN_ARCHITECTURE.html) — same doc for browser / print / PDF

### Implementation status (vertical plugins)

| Phase | Status |
|-------|--------|
| 1 — Foundation | **Shipped** |
| 2 — Dynamic UI | **Mostly shipped** (platform) |
| 3 — Extension storage | **Shipped** |
| 4 — Search providers | **Shipped** |
| 5 — Second vertical proof | **Partial** (sports done; apparel/cafe planned) |
| 6 — Import, widgets | Planned |
| 7 — Scale & analytics | Planned |

### Vertical plugins & schema API

## GSTIN verification and where a party is (taxation module)

Vendor and customer GSTINs are checked in three steps: offline format + check character (`Gstin` in `core/common`), the `gstin_registry` collection (one record per GSTIN, shared by every shop), and only for a GSTIN never seen before, one call to the configured provider (`GstinLookupProvider` in `core/taxation`). Switch providers with `GSTIN_PROVIDER` (`gstinapi` needs `GSTINAPI_KEY`; `none` keeps offline checks only). Vendors save either way — a failed lookup leaves the vendor unverified, never blocked.

Whether a supply is interstate (IGST) or local (CGST + SGST) is decided in one place, `SupplyPlacement` (implemented by `SupplyPlacementService` in taxation): shop by its GSTIN then address; vendor/customer by the registry record, then their GSTIN, then the state on their address (`postalAddress.stateCode`). A vendor must have a valid GSTIN or that state. Stock-in writes the answer on the purchase invoice (`interstate`) and the ledger, vendor returns and GSTR-2 read it from there.

Shops are bound to a **vertical** (`Shop.verticalId` + `Shop.pluginVersion`). Field definitions live in MongoDB (`vertical_schemas`), seeded from `core/product/src/main/resources/seeds/*.json` on boot.

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/verticals` | List ACTIVE verticals (onboarding catalog) |
| `GET /api/v1/verticals/{verticalId}/schema?mode=` | Public schema preview (no shop context) |
| `GET /api/v1/shops/me/schema?mode=regular\|basic\|invoice` | Filtered schema for logged-in shop UI |

**Inventory search & expiry (Phase 4):**

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/inventory/search` | Unified `q`, `sort`, `limit`, `cursor` → `meta.nextCursor` |
| `GET /api/v1/inventory/expiry-buckets` | Expiry bucket counts (Analytics) |
| `GET /api/v1/reminders/expiry-buckets` | Expiry bucket cards (Reminders dashboard) |

**Removed:** `GET /inventory/near-expiry`, `GET /inventory/fefo`.

**Modes:** `regular` (full registration), `basic` (quick stock-in), `invoice` (print surfaces). Required fields respect `showIn` per mode after Phase 2 filter updates.

- **`basic` needs explicit tags.** A required field reaches `basic` only when its `showIn` is empty or lists `"basic"`; an optional field needs `tier: "basic"` or `"basic"` in `showIn`. A seed with no such fields returns an empty inventory list, and stock-in in Basic mode cannot add products. `VerticalSchemaSeedFilesTest` checks the sports seed.
- **Sports inventory fields** (`sports-v1.json`, stored in `inventory_ext_sports`): required `sport` (enum), `brand`, `model`; optional `size` (string, max 32) and `warrantyMonths`. `size` is tagged `"basic"` in `showIn`, so it appears in quick stock-in too; `warrantyMonths` stays regular-only.
- **Shop licence fields** sit in the seed's `shop` entity with `showIn: ["onboarding"]`: `dlNo` (medical), `fssai` (grocery, cafe).

**After changing seed JSON**, restart the app: `VerticalSchemaSeeder` overwrites rows it created (`createdBy` starts with `seed:`) and then `SchemaLoader.warmCache()` runs. Rows published another way are skipped, so delete or update those (e.g. `medical_1.0.0`) by hand.

**Seed mirrors:** `docs/seeds/medical-v1.json`, `docs/seeds/sports-v1.json`

**Frontend (inventory-platform):** consumes schema APIs for onboarding, product registration, scan-sell, product search (cursor pagination), and reminders expiry buckets — see architecture doc § Implementation status.

**Remaining:** M8 core field strip migration; scan-sell detail modal schema columns; apparel/cafe vertical (Phase 5); import mappers + widgets (Phase 6).

### GST reports (taxation)

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/taxation/gstr1` | Outward supplies (sales) |
| `GET /api/v1/taxation/gstr2` | Inward supplies (purchases) |
| `GET /api/v1/taxation/gstr3b` | Summary return |

**Shop state is required for GSTR-1 and GSTR-2.** The shop's state comes from its GSTIN, else the state on its address. Without either, `GET /taxation/gstr1` and `GET /taxation/gstr2` (and their downloads) return **422** with error code **7000 `GST_CONFIGURATION_MISSING`**, because an interstate supply (IGST) cannot be told from a local one (CGST + SGST). GSTR-1 previously wrote an empty place of supply instead. Set the shop's GSTIN or address state, then generate the return again.

**IGST on interstate sales.** A sale to a customer whose GSTIN places them in another state is billed as IGST at the combined rate (CGST and SGST zero); an unregistered or unplaceable customer is local. The decision is `GstStateCode.isInterstate` (`core/common/util`), shared by checkout and GSTR-2, and is stored on the sale (`interstate`, `igstAmount`) so GSTR-1 (B2B, B2CL, B2CS and the HSN summary, under IGST), the invoice and estimate conversion read it rather than deciding again. Sale responses (`PurchaseSummaryDto`, `AddToCartResponse`, `CheckoutResponse`) carry `igstAmount` and `interstate`, and `taxSummary` gives IGST per rate (`rates[].igstAmount`, `igstTotal`). Printed invoices (A4, thermal, dot matrix) read the sale's `interstate` flag: an interstate bill prints an IGST% column from each line's combined rate and one IGST row per rate, never CGST/SGST beside it. The journal posts output IGST. A return of an interstate sale reverses IGST (`SalesReturnValuation`), posts it as output IGST, and its credit note states IGST.

**Purchase tax basis.** GSTR-2 and the purchase journal both take each supplier invoice's tax from `PurchaseTaxBasisResolver` (`core/product/.../utils`), which works it out from the lines: quantity × cost after the percentage scheme and additional discount (free units do not reduce it), less the bill-level discount shared across lines by value, then tax at each lot's rate added on top, or taken out for a bill marked `INCLUSIVE`.

**Purchase tax at stock-in.** `POST /api/v1/inventory/bulk` with a `vendorPurchaseInvoice` header:

- **Totals:** line subtotal, tax total and invoice total are saved from the request (the stock-in screen sends the server's bill preview figures). `PurchaseTaxRecorder` stores the per-line taxable value and tax, and fills in any total the client left out from the lines. The line subtotal is before the bill-level discount; the invoice total is taxable + tax + shipping + other charges + round-off.
- **Refused (400, validation errors):** a negative line subtotal, tax total, invoice total, shipping charge, other charges or overall discount. Rules live in `VendorPurchaseInvoiceValidator`.
- **Never blocks stock-in:** if the totals cannot be worked out, they stay empty and the reports resolve the lines on read.

**Landed cost and GST.** `pricing.effectiveCostPrice` is the cost after the purchase scheme and additional discount, **before GST**. A lot stocked in from a bill marked `INCLUSIVE` has `pricing.costPriceIncludesTax: true` (set from the bill's tax treatment, never from the client), and `PricingUtils.computeEffectiveCostPrice` takes the GST out at the lot's rate (`landed × 100 / (100 + sgst + cgst)`): 99 less 24% at 5% inclusive lands at 71.6571, so 36 units value at 2579.66, the bill's taxable value. Exclusive lots and records written before the flag are unchanged. Margin, stock valuation and COGS read this figure.

**Previewing a bill before stock-in.** `POST /api/v1/vendor-purchase-invoices/preview-totals` takes the stock-in screen as it stands (`vendorId`, `taxTreatment`, the same `items` rows `POST /inventory/bulk` takes, and any typed `shippingCharge`, `otherCharges`, `overallDiscount`, `roundOff`) and returns `taxTreatment` with `taxTreatmentSource` (`STATED`, `LINES`, `VENDOR`, `NONE`), `lineSubTotal`, `taxTotal`, `itemsTotal` (items only), `invoiceTotal`, `productCount`, `totalQuantity` and per-row `lines` (`taxable`, `ratePct`, `centralTax`, `stateTax`, `integratedTax`, `tax`). Nothing is saved. It runs the stock-in rules (same item mapping, tax treatment, `PurchaseTaxBasisResolver` and `PurchaseTaxRecorder` totals), so the figures it returns are the ones stock-in stores, and the frontend shows them read-only instead of computing GST or totals itself.

**GST rates offered for an HSN.** `GET /api/v1/taxation/hsn-gst-rates?hsn=33061020` returns the rates the CBIC notifications allow for the HSN, each split into CGST and SGST (`rates[]: gstRate, cgst, sgst`), with the code that matched (`matchedHsn`, the HSN or its six- or four-digit parent) and the notification entries (`ref`). The stock-in row offers them as GST choices; CGST and SGST stay editable for an HSN not on file or goods the table does not name. Rates come from `core/taxation/src/main/resources/hsn/hsn-gst-rates.json` (`HsnGstRateMaster`, beside the HSN descriptions in `hsn-sac-master.json`, 1,204 codes from 9/2025, 10/2025, 19/2025 and 01/2026-CT(Rate)); an HSN spanning two entries lists both (3306: 5% or 18%). HSN codes are read by `common/util/HsnCodes`, shared with the GSTR HSN descriptions (`HsnSacCatalog`).

**Vendor tax treatment.** A vendor carries `defaultTaxTreatment` (`EXCLUSIVE` or `INCLUSIVE`, set on `POST`/`PUT /api/v1/vendors`). A stock-in that leaves the invoice's treatment empty reads it from the lines first: every line costed at its MRP is `INCLUSIVE` (MRP always includes GST), every line costed below its MRP is `EXCLUSIVE`; lines that disagree, or have no MRP, decide nothing and the vendor's default applies (`PurchaseTaxTreatmentResolver`). A treatment chosen on the bill, or taken from the vendor, is still checked against the rows: if they contradict it, the preview returns `taxTreatmentConflict` (a message) and `taxTreatmentFromLines`, and `POST /inventory/bulk` refuses the bill (400, same message) unless `vendorPurchaseInvoice.confirmTaxTreatment: true` is sent -- the operator confirms it from the paper. A stock-in that states one explicitly also updates the vendor's default (the latest bill wins); leaving it empty changes nothing.

**Amending a purchase invoice header.** `PATCH /api/v1/vendor-purchase-invoices/{id}` corrects the header against the paper bill (shipping, other charges, overall discount, round-off, tax treatment) and needs a `reason`. The subtotal, tax and invoice total are worked out again from the lines. Lines are not amendable. The previous header, who changed it and why are kept on the invoice. The amendment is validated like stock-in, the totals are worked out again, and the purchase journal entry is **reversed and posted again** with the corrected figures (the invoice's `ledgerSourceId` names the live entry). The vendor's credit ledger (`core/credit`) is not adjusted yet. Before saving, `POST /api/v1/vendor-purchase-invoices/{id}/amend-preview` (same body, reason optional) works the correction out without saving and returns `saved` and `corrected` header figures, `changedFields` and `journalReposted`, so the form shows the operator what moves. A correction that would change nothing is refused (400). The invoice detail carries `previousHeader` after a correction.

### Build commands

```bash
# Build everything
mvn clean install

# Build only app and its dependencies
mvn -pl app -am clean package

# Build a single module (e.g. product)
mvn -pl core/product compile

# Run tests
mvn test
```

---

## Project Structure

```
inventory-api/
├── .env.example              # Example environment variables
├── docker-compose.yml        # Docker Compose (app + image-preprocess)
├── Dockerfile                # Multi-stage build for app
├── pom.xml                   # Parent POM
│
├── app/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/inventory/app/
│       │   ├── AppApplication.java      # Main entry point
│       │   ├── config/                  # CORS, security, Mongo
│       │   └── interceptor/             # Auth interceptor
│       └── resources/
│           ├── application.properties
│           └── static/
│
├── core/
│   ├── pom.xml
│   ├── common/
│   ├── product/
│   ├── plan/
│   ├── pricing/
│   ├── user/
│   ├── documentservice/
│   ├── ocr/
│   ├── reminders/
│   ├── notifications/
│   ├── analytics/
│   └── taxation/
│
├── plugins/
│   ├── pom.xml
│   └── medical/
│
└── pluginengine/
```

### Typical per-module layout

Each core module usually follows:

```
core/<module>/
├── pom.xml
└── src/main/java/com/inventory/<module>/
    ├── domain/
    │   ├── model/           # Entities
    │   ├── repository/      # Spring Data repositories
    │   └── enums/           # Domain enums
    ├── service/             # Business logic
    ├── rest/
    │   ├── controller/      # REST controllers
    │   └── dto/             # Request/response DTOs
    ├── mapper/              # MapStruct mappers
    ├── validation/          # Validators
    └── utils/               # Module-specific utilities
```

---

## Contributing Guidelines

### Code style and conventions

1. **Java 21** – Use language features appropriately.
2. **Formatting** – Use project formatter and follow existing style (indentation, line length).
3. **Naming** – Use clear, consistent names; follow Java conventions.
4. **Dependencies** – Add new ones only when necessary; prefer existing libraries.

### Architecture

1. **Layering** – Keep REST, service, domain, and repository layers separate.
2. **Mappers** – Use MapStruct for DTO ↔ entity mapping instead of manual setters.
3. **Validators** – Use dedicated validators for input validation instead of inline logic in services.
4. **Utils** – Extract reusable logic into small static utils (e.g. `CheckoutUtils`) rather than large service methods.
5. **Dependencies** – Core modules should not depend on `app`; `app` aggregates core modules.

### Service layer

- Keep services focused and avoid deep nesting.
- Use mappers for object construction; avoid manual `new` + setters where a mapper exists.
- Validate inputs via validators before processing.
- Avoid business logic in controllers.

### Testing

- Add unit tests for non-trivial logic.
- Add integration tests for important flows where appropriate.
- Run `mvn test` before pushing.

### Pull requests

1. Create a feature branch from `main` (or default branch).
2. Make focused commits with clear messages.
3. Ensure build and tests pass locally.
4. Update docs if behavior or setup changes.
5. Request review from maintainers.
6. Resolve feedback before merge.

### Branch naming

- `feature/<short-description>` – New features
- `fix/<short-description>` – Bug fixes
- `refactor/<short-description>` – Refactoring
- `docs/<short-description>` – Documentation only

### Commit messages

Use clear, imperative-style messages, for example:

```
Add PurchaseMapper.toPurchaseListResponse for paginated responses
Fix inventory billing mode validation in checkout
Refactor CheckoutService to use CheckoutUtils
```

---

## License

See the project license file for details.
