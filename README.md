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

Optional (Stockkart Team; see [Stockkart Team (worker-app)](#stockkart-team-worker-app)):

| Variable | Property | Description | Default |
|----------|----------|-------------|---------|
| `GOOGLE_OAUTH_CLIENT_IDS` | `google.oauth.client-ids` | Comma-separated OAuth client ids whose Google ID tokens `GoogleTokenVerifier` accepts: the web client id and the Stockkart Team app's Android client id. A token for any other audience is `INVALID_CREDENTIALS`. Empty = every Google token is refused | empty |
| `HR_WORKER_EXIT_ENABLED`, `HR_WORKER_EXIT_INTERVAL_MS` | `hr.worker-exit.*` | `WorkerExitJob`: ends the `WORKER` membership of linked employees whose exit date has arrived and resets join requests left `APPROVING` for over 5 minutes. Same shape as `HR_PAIRING_EXIT_*` (`PairingExitJob`); listed in `.env.example` and `docker-compose.yml` with the defaults, which are right everywhere | `true`, `60000` |

Only the first two are in `.env.example` and `docker-compose.yml`; the job switches are `application.properties` defaults.

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
| `PUT /api/v1/inventory/{id}` | Edit one lot (product search Edit). Sent fields only; prices go to the lot's pricing record, and the response carries the prices as stored after the update |

**Removed:** `GET /inventory/near-expiry`, `GET /inventory/fefo`.

**Modes:** `regular` (full registration), `basic` (quick stock-in), `invoice` (print surfaces). Required fields respect `showIn` per mode after Phase 2 filter updates.

- **`basic` needs explicit tags.** A required field reaches `basic` only when its `showIn` is empty or lists `"basic"`; an optional field needs `tier: "basic"` or `"basic"` in `showIn`. A seed with no such fields returns an empty inventory list, and stock-in in Basic mode cannot add products. `VerticalSchemaSeedFilesTest` checks the sports seed.
- **Sports inventory fields** (`sports-v1.json`, stored in `inventory_ext_sports`): required `sport` (enum), `brand`, `model`; optional `size` (string, max 32) and `warrantyMonths`. `size` is tagged `"basic"` in `showIn`, so it appears in quick stock-in too; `warrantyMonths` stays regular-only.
- **Shop licence fields** sit in the seed's `shop` entity with `showIn: ["onboarding"]`: `dlNo` (medical), `fssai` (grocery, cafe).

**After changing seed JSON**, restart the app: `VerticalSchemaSeeder` overwrites rows it created (`createdBy` starts with `seed:`) and then `SchemaLoader.warmCache()` runs. Rows published another way are skipped, so delete or update those (e.g. `medical_1.0.0`) by hand.

**Seed mirrors:** `docs/seeds/medical-v1.json`, `docs/seeds/sports-v1.json`

**Frontend (inventory-platform):** consumes schema APIs for onboarding, product registration, scan-sell, product search (cursor pagination), and reminders expiry buckets — see architecture doc § Implementation status.

**Remaining:** M8 core field strip migration; scan-sell detail modal schema columns; apparel vertical (Phase 5); import mappers + widgets (Phase 6).

### Cafe kitchen tickets

Cafe KOT lives in `plugins/cafe`. Calls between it and `core/product` go through `pluginengine` (`CafeKotPort`, implemented by `CafeKotAdapter`); the plugin imports nothing from core. The punch reads and updates the bill (`items[].kotSentQuantity` and `cafeKotPunches` in `purchases`) only through `CafeCartPunchPort`, which `core/product` implements (`CafeCartPunchPortImpl`, with the single-write pipeline in `CafeCartPuncher`). Ticket PDFs are rendered through `documentservice`'s `KotDocuments` api.

The Sell screen punches a cart and the server owns the delta: each line sends `baseQuantity - kotSentQuantity`, so a second press sends only what was added, and a press with nothing new creates nothing. Only an open bill (`CREATED` / `PENDING`) of a cafe shop can be punched. Tickets are grouped by station (`department`) and have deterministic ids (`punchId:department:kind`); they are **inserted**, never saved, so a concurrent recovery of the same punch reads the stored ticket back instead of renumbering it. Reprint is a field-level update of `reprintCount` plus `reprintIdempotencyKey` -- a replay of the same key does not bump the count.

Indexes are not created by the app. Before deploying, create the indexes declared on `CafeKot` (`shop_punch`, `shop_purchase_created`), `cafe_sequences` (`shop_date_series_unique`, unique `{shopId, businessDate, series}`) and `cafe_token_counters` (`shop_date_scope_unique`, unique `{shopId, businessDate, scope}`), and stamp legacy `cafe_token_counters` rows that have no `scope` with `scope: BILL`. These are run by hand against the database; no script is kept in the repo.

| Endpoint | Purpose |
|----------|---------|
| `POST /api/v1/cafe/purchases/{purchaseId}/kots` | Punch the cart. Requires `Idempotency-Key`. No body. |
| `GET /api/v1/cafe/purchases/{purchaseId}/kots` | Tickets this bill has issued, newest first (the Sell screen's "Sent rounds"). |
| `GET /api/v1/cafe/kots/{kotId}/document` | Thermal PDF for a ticket. |
| `POST /api/v1/cafe/kots/{kotId}/reprint` | Reprint stamp. Requires `Idempotency-Key`. Same key is a no-op bump. |

**Clearing a field on a lot edit.** In `PUT /api/v1/inventory/{id}` a field left out or sent as `null` keeps its value. To empty one, name it in `clearFields`: `barcode`, `description`, `companyName`, `location`, `hsn`, `discountApplicable`, `thresholdCount` (lot) and `saleAdditionalDiscount`, `purchaseAdditionalDiscount`, `saleScheme`, `purchaseScheme` (pricing record). Any other name, or a name also sent with a value, is a 400. A clear needs the same product-search grant as setting the field (`saleScheme` → `schemeType`, `purchaseScheme` → `purchaseSchemeType`).

**Granting product-search fields to a member.** In a permission-based shop the owner grants each member fields with `PATCH /api/v1/shops/{shopId}/rbac/members/{userId}` (`productSearchEditableFields`). Only the fields in `RbacService.CORE_PRODUCT_SEARCH_FIELDS` can be granted (name, description, company, location, batch, expiry, cost, PTR, MRP, selling price, HSN, CGST, SGST, barcode, base unit, pack size). Any other name is a 400 naming it. The exception is a name the member already held, which is dropped, so the access screen can still save a member whose stored grants predate the list. Rates, the default rate, discounts and schemes are therefore editable only by the owner, or by members with edit access in a full-edit shop.

### Cafe menu portions

A cafe menu item is priced **either** by one `sellingPrice` **or** by named portions (`rates`: Qtr / Half / Full, each a `MenuRate { id, name, price }`), never both. The portion `id` is a slug frozen when the portion is first named (`MenuRates.normalize`, run on save before validation), so renaming "Half" never orphans a cart line. A cart line names its portion in the sellable ref, `menu:<itemId>@<rateId>` (`SellableRef`), and the price is always resolved from the menu on the server, never taken from the request. Validation (`CafeMenuVerticalValidator`): every portion needs an id, a name and a positive price, ids and names unique within the item.

Menu lines also carry the kitchen `department` (station, blank means `KITCHEN`, see `MenuDepartments`) and an optional `note`, frozen onto the cart line when it is added.

### Cafe stock items in menu sections

A sell-direct stock lot can be placed in a menu section as a `MenuItem` with `sellMode: direct` and `inventoryId` (no new collection). The lot keeps owning price and stock: `MenuDirectLinks.normalize` clears any price, portions or GST sent on a placement, and the cart still sells it by its `inventory:` ref, so checkout, stock decrement and refunds are unchanged. The placement decides only the station: a stock cart line freezes the placement's `department` (`ShopMenuLookup.findDirectLink`), and an unplaced lot keeps `KITCHEN`. A placement's own `menu:` ref is refused for sale.

`NONE` is a real station meaning "billed, but no ticket" (a bottle from the counter fridge): the punch claims the line but creates no KOT and burns no number (`MenuDepartments.emitsTicket`). Saving the menu: a lot may be placed once ("Lassi is already in Beverages"), a save over a stored menu must carry its revision, and a placement whose lot was deleted stays saveable.

### Catalog products at stock-in (product)

Every stock-in line is linked to a shop `product` (catalog identity: name, company, barcode, HSN, packaging, …) by `ProductService.resolveForRegistration`; lot edits (`PUT /api/v1/inventory/{id}`) go through it too. It reuses the shop's existing product whenever the line describes the same item, so one medicine is not stored as several products:

- **Loose comparison.** Company, description and unit ignore case and repeated spaces; the business type ignores case and reads `PHARMACEUTICAL` and `PHARMACY` as `MEDICAL`; a missing item type is `NORMAL`. Name, HSN, item type and pack size must still agree.
- **Barcodes.** A line that was not picked from the suggestions reuses a product only with the same barcode, or with none on both. A barcode separates variants that share a name (a sports model or size, a pack size), so a line never takes over a product with a different barcode or none.
- **Picked product.** When the user picked a product (or edits a lot) and the line still matches it, that product is kept and corrected in place: a new barcode replaces its own, unless another copy of the item already carries that code (that copy is reused instead); company, description and unit take the line's spelling; business type and item type are only upgraded from an older spelling (`PHARMACEUTICAL`, `PHARMACY`, missing), never downgraded. A stock-in line is saved as the user sees it: a blank barcode leaves the product without one (the product entry screen fills the barcode in when a suggestion is picked). A lot edit changes the barcode only when it sends one, or empties it with `barcode` in `clearFields`. When the line no longer matches, the shop is searched for a matching product before a new one is created. Among several matching copies, the first one found is reused.

`GET /api/v1/products/suggest?q=` offers each product once: copies of one item fold into one row (the copy with a barcode first, then the oldest), while copies with different barcodes stay separate rows. Up to 10 rows.

Products duplicated before this rule are not merged automatically.

### Cart and settlement writes

`CheckoutService` no longer replaces a bill with `purchaseRepository.save`. Cart edits and settlement go through `PurchaseTargetedWriter`, which writes only the fields that changed (lines addressed by `lineRef`, else `sellableRef`), so a concurrent writer -- a kitchen punch recording `kotSentQuantity` -- is never deleted by a cart write. A cart write that cannot address its line is refused rather than replacing the bill.

Settlement is guarded: if the bill changed after its total was computed, the write is refused (400, "This bill changed while it was being settled") and nothing irreversible has happened. Stock is only checked before that write and taken off after it succeeds, so a refused settle and its retry never decrement stock twice. There is no Mongo transaction manager; nothing here relies on multi-document atomicity.

### GST reports (taxation)

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/taxation/gstr1` | Outward supplies (sales) |
| `GET /api/v1/taxation/gstr2` | Inward supplies (purchases) |
| `GET /api/v1/taxation/gstr3b` | Summary return |

**Shop state is required for GSTR-1 and GSTR-2.** The shop's state comes from its GSTIN, else the state on its address. Without either, `GET /taxation/gstr1` and `GET /taxation/gstr2` (and their downloads) return **422** with error code **7000 `GST_CONFIGURATION_MISSING`**, because an interstate supply (IGST) cannot be told from a local one (CGST + SGST). GSTR-1 previously wrote an empty place of supply instead. Set the shop's GSTIN or address state, then generate the return again.

**Return months and dates are Indian time.** GSTR-1, GSTR-2 and GSTR-3B take a month as midnight to midnight in Asia/Kolkata and date every document by its Indian date (`GstPeriods` in `core/taxation/utils`), whatever zone the server runs in. They used the JVM default zone, so on a UTC server a bill made between 00:00 and 05:30 IST on the first of a month went into the previous month, dated the previous day.

**Which sales and notes a GSTR-1 period holds.** A sale belongs to the month of its `soldAt`; `updatedAt` counts only for a legacy sale with no `soldAt`. (Before, any sale edited, paid or renumbered in a later month was filed again in that month.) A credit note belongs to the month it was raised (`createdAt`), whatever month its sale was in. Each note is split per rate from its lines (each line's refund amount, tax backed out at the sale's rate, the same reading the sale gets) and taken off the tables its sale went into:

| Buyer of the sale | Note reported on | Also taken off |
|---|---|---|
| Registered (GSTIN) | `cdnr` (workbook sheet and portal JSON) | B2B HSN summary |
| Unregistered, B2CL sale | `cdnur` (workbook only; the JSON has no B2CL/CDNUR yet) | B2C HSN summary |
| Unregistered, any other sale | nowhere of its own (cdnur is only for B2CL and exports) | `b2cs` and B2C HSN summary |

Every note is one "Credit Note" series in the document summary, written to the JSON as `doc_num` 5 (the portal's code, not a position). The JSON's HSN rows leave `desc` empty, as in the returns filed by hand; the workbook keeps the description.

**IGST on interstate sales.** A sale to a customer whose GSTIN places them in another state is billed as IGST at the combined rate (CGST and SGST zero); an unregistered or unplaceable customer is local. The decision is `GstStateCode.isInterstate` (`core/common/util`), shared by checkout and GSTR-2, and is stored on the sale (`interstate`, `igstAmount`) so GSTR-1 (B2B, B2CL, B2CS and the HSN summary, under IGST), the invoice and estimate conversion read it rather than deciding again. Sale responses (`PurchaseSummaryDto`, `AddToCartResponse`, `CheckoutResponse`) carry `igstAmount` and `interstate`, and `taxSummary` gives IGST per rate (`rates[].igstAmount`, `igstTotal`). Printed invoices (A4, thermal, dot matrix) read the sale's `interstate` flag: an interstate bill prints an IGST% column from each line's combined rate and one IGST row per rate, never CGST/SGST beside it. The journal posts output IGST. A return of an interstate sale reverses IGST (`SalesReturnValuation`), posts it as output IGST, and its credit note states IGST.

**Lines sold at MRP.** A cart line whose price equals its MRP is billed with the GST inside the MRP, never on top. It stays at MRP when its sale unit is switched (strip to tablet and back) or its quantity changed: the line MRP and price are reset to the batch MRP for that unit (`CheckoutService.updateCart`, `resolveMaximumRetailPriceForSaleUnit`), and a per-tablet MRP is rounded down so the bill never exceeds the MRP. A price sent with a quantity change is ignored; a price-only edit (no quantity change) is kept as typed, so the line may leave MRP. A return of an at-MRP line states the GST inside it (`SalesReturnValuation`, sharing `CheckoutUtils.getTaxableLineValue` and `gstHeadAmount` with the sale): CGST and SGST use the line's own rates, i.e. the same heads as the sale; an interstate sale gets IGST = CGST + SGST with CGST and SGST zero. The refund amount does not change; taxable value is the line total less the GST. It does so only when the original sale stated that GST (`SalesReturnValuation.statedAtMrpGst`): no sale line records it, so the evidence is the sale header, whose tax, less the below-MRP lines' GST, must cover at least half the GST inside its at-MRP lines (a below-MRP line without its own rates counts at the shop's default CGST/SGST, as the sale taxed it). Sales made before #229 stated none, so their at-MRP returns keep zero GST with the whole amount taxable until those bills are backfilled. Lines below MRP and composition (BASIC) billing are otherwise unchanged.

**Unit switch below MRP.** A switch keeps the line total. A price sent with it is for the new unit (ScanSell rescales the price before sending, 140 a strip to 14.00 a tablet) and goes on as sent; with no price sent, the line's own price is rescaled to the new unit. Before, a sent price was rescaled a second time (14.00 became 1.40). Returns saved earlier are not restated.

**Returns in pieces.** A line may be returned over several returns. Each return's amounts (taxable, each GST head, COGS, refund) are the sold line's amounts for everything returned so far, this return included, less those for what earlier returns took back (`SalesReturnValuation.lineAmounts`, earlier returns read from the sale's refund records by `RefundService`). Each is a share of the sold line's billable quantity, never the scheme worked out again on the returned quantity, and is rounded once, so the pieces of a line add up to returning it whole: an at-MRP line's GST heads to the sale's heads and its refunds to what the customer paid. Refunds cannot be cancelled, so every refund record of the sale counts.

**Return history states the credit note's figures.** `GET /api/v1/refund` gives each return `taxableTotal`, `cgstAmount`, `sgstAmount`, `igstAmount`, `taxTotal`, `roundOff` and `interstate` beside `refundAmount` (the grand total), and each line `gstRatePct` (CGST + SGST rate, e.g. 5) and `igstAmount`. They come from `CreditNoteFigures` (`core/product/.../utils`), which the printed credit note also reads, so the screen and the paper cannot disagree. A refund saved before its header carried totals is stated from its lines, and its round-off is whatever separates the refund from taxable value plus tax.

**Purchase tax basis.** GSTR-2 and the purchase journal both take each supplier invoice's tax from `PurchaseTaxBasisResolver` (`core/product/.../utils`), which works it out from the lines: quantity × cost after the purchase scheme and additional discount, less the bill-level discount shared across lines by value, then tax at each lot's rate added on top, or taken out for a bill marked `INCLUSIVE`.

A deal ratio counts as a scheme: the line count is everything received, so "19+1" makes the line `count × cost × 19/20`. MAHAMAYA A012392 bills 20 at 121.90 less 19+1 less 8% as 2130.81 taxable, and 600 received as 540 paid + 60 free under 9+1 is taxed on the 540. Bills stocked in from 2026-10-04 until this fix left the deal ratio out, so their stored taxable value and tax are overstated.

**Purchase tax at stock-in.** `POST /api/v1/inventory/bulk` with a `vendorPurchaseInvoice` header:

- **Totals:** line subtotal, tax total and invoice total are saved from the request (the stock-in screen sends the server's bill preview figures). `PurchaseTaxRecorder` stores the per-line taxable value and tax, and fills in any total the client left out from the lines. The line subtotal is before the bill-level discount; the invoice total is taxable + tax + shipping + other charges + round-off.
- **Refused (400, validation errors):** a negative line subtotal, tax total, invoice total, shipping charge, other charges or overall discount. Rules live in `VendorPurchaseInvoiceValidator`.
- **Never blocks stock-in:** if the totals cannot be worked out, they stay empty and the reports resolve the lines on read.

**Landed cost and GST.** `pricing.effectiveCostPrice` is the cost after the purchase scheme and additional discount, **before GST**. A lot stocked in from a bill marked `INCLUSIVE` has `pricing.costPriceIncludesTax: true` (set from the bill's tax treatment, never from the client), and `PricingUtils.computeEffectiveCostPrice` takes the GST out at the lot's rate (`landed × 100 / (100 + sgst + cgst)`): 99 less 24% at 5% inclusive lands at 71.6571, so 36 units value at 2579.66, the bill's taxable value. Exclusive lots and records written before the flag are unchanged. Margin, stock valuation and COGS read this figure.

**Previewing a bill before stock-in.** `POST /api/v1/vendor-purchase-invoices/preview-totals` takes the stock-in screen as it stands (`vendorId`, `taxTreatment`, the same `items` rows `POST /inventory/bulk` takes, and any typed `shippingCharge`, `otherCharges`, `overallDiscount`, `roundOff`) and returns `taxTreatment` with `taxTreatmentSource` (`STATED`, `LINES`, `VENDOR`, `NONE`), `lineSubTotal`, `taxTotal`, `itemsTotal` (items only), `invoiceTotal`, `productCount`, `totalQuantity` and per-row `lines` (`taxable`, `ratePct`, `centralTax`, `stateTax`, `integratedTax`, `tax`). Nothing is saved. It runs the stock-in rules (same item mapping, tax treatment, `PurchaseTaxBasisResolver` and `PurchaseTaxRecorder` totals), so the figures it returns are the ones stock-in stores, and the frontend shows them read-only instead of computing GST or totals itself.

**GST rates offered for an HSN.** `GET /api/v1/taxation/hsn-gst-rates?hsn=33061020` returns the rates the CBIC notifications allow for the HSN, each split into CGST and SGST (`rates[]: gstRate, cgst, sgst`), with the code that matched (`matchedHsn`, the HSN or its six- or four-digit parent) and the notification entries (`ref`). The stock-in row offers them as GST choices; CGST and SGST stay editable for an HSN not on file or goods the table does not name. Rates come from `core/taxation/src/main/resources/hsn/hsn-gst-rates.json` (`HsnGstRateMaster`, beside the HSN descriptions in `hsn-sac-master.json`, 1,204 codes from 9/2025, 10/2025, 19/2025 and 01/2026-CT(Rate)); an HSN spanning two entries lists both (3306: 5% or 18%). HSN codes are read by `common/util/HsnCodes`, shared with the GSTR HSN descriptions (`HsnSacCatalog`).

**Vendor tax treatment.** A vendor carries `defaultTaxTreatment` (`EXCLUSIVE` or `INCLUSIVE`, set on `POST`/`PUT /api/v1/vendors`). A stock-in that leaves the invoice's treatment empty reads it from the lines first: every line costed at its MRP is `INCLUSIVE` (MRP always includes GST), every line costed below its MRP is `EXCLUSIVE`; lines that disagree, or have no MRP, decide nothing and the vendor's default applies (`PurchaseTaxTreatmentResolver`). A treatment chosen on the bill, or taken from the vendor, is still checked against the rows: if they contradict it, the preview returns `taxTreatmentConflict` (a message) and `taxTreatmentFromLines`, and `POST /inventory/bulk` refuses the bill (400, same message) unless `vendorPurchaseInvoice.confirmTaxTreatment: true` is sent -- the operator confirms it from the paper. A stock-in that states one explicitly also updates the vendor's default (the latest bill wins); leaving it empty changes nothing.

**Amending a purchase invoice header.** `PATCH /api/v1/vendor-purchase-invoices/{id}` corrects the header against the paper bill (shipping, other charges, overall discount, round-off, tax treatment) and needs a `reason`. The subtotal, tax and invoice total are worked out again from the lines. Lines are not amendable. The previous header, who changed it and why are kept on the invoice. The amendment is validated like stock-in, the totals are worked out again, and the purchase journal entry is **reversed and posted again** with the corrected figures (the invoice's `ledgerSourceId` names the live entry). The vendor's credit ledger (`core/credit`) is not adjusted yet. Before saving, `POST /api/v1/vendor-purchase-invoices/{id}/amend-preview` (same body, reason optional) works the correction out without saving and returns `saved` and `corrected` header figures, `changedFields` and `journalReposted`, so the form shows the operator what moves. A correction that would change nothing is refused (400). The invoice detail carries `previousHeader` after a correction.

### Rate rules (product, pricing)

A shop can define named rate rules once (for example `Rate-A` = PTR - 8% + 10+1) and every batch gets the matching rates generated from its own PTR or MRP. The rules live in `shop_rate_rules` (`ShopRateRulesDocument`, one per shop, unique `shopId` index `shop_rate_rules_shop_unique_idx`, created by hand on each database). All arithmetic is in `RateRuleCalculator` (`core/pricing`); the settings code only stores rules and previews them.

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/shops/active-shop/rate-rules` | `enabled` (false for cafe), `baseOptions` (`sellingPrice` for a RETAILER; `priceToRetail`, `maximumRetailPrice` otherwise), `rules`, and `canEdit` for the caller. Shop access only. |
| `PUT /api/v1/shops/active-shop/rate-rules` | Replaces the ordered rule list. Needs the `rateRulesEdit` grant (owner always; others only when the owner grants it on the access screen), else 403 `ACCESS_DENIED`. Two saves at once: a racing first save is retried once on the document the other created; a save on a stale version (`ShopRateRulesDocument.version`) is 409 `RATE_RULES_CONFLICT` (3004). |
| `POST /api/v1/shops/active-shop/rate-rules/preview` | Body: base values, the raw cost inputs (`costPrice`, `costPriceIncludesTax`, `purchaseAdditionalDiscount`, `purchaseScheme`, `sgst`, `cgst`) and optionally unsaved `rules`. Returns each generated rate (`baseValue`, `afterDiscount`, `price`, `gstMode`, `termsText`) and the rules skipped below cost. Cost is resolved as on save. |

A rule needs a discount above 0 (up to 100), a scheme, or both. Names are unique ignoring case and may not be a built-in price's name (`SystemRateNames`): `priceToRetail`, `maximumRetailPrice`, `costPrice`, or the counter labels `PTR`, `MRP`, `Cost`, in any case. Base options depend on the shop type: a RETAILER may use only `sellingPrice`; every other non-cafe shop uses `priceToRetail` or `maximumRetailPrice`; a cafe has no rate rules. Errors (all 400): 3002 `RATE_RULE_INVALID` (bad rule, or `PUT` on a cafe shop), 3003 `RATE_RULE_BASE_NOT_ALLOWED` (base not offered to this shop type), and 1006 `VALIDATION_ERROR` for a bad scheme shape. A denied edit is 403 `ACCESS_DENIED` (1003). Shops with rules report `features.rateRules = true` in their capabilities.

**Generation and recalculation.** A rate's price is `base x (1 - disc/100)`, then the scheme (percentage cut, or for fixed units `x pay-for / (pay-for + free)` in one divide, so PTR 500 - 8% at 10+1 is 418.18), rounded to 2 dp once at the end (`RateRuleCalculator`). The disc % and a percentage scheme are divided by 100 at 6 dp, as the purchase cost is (`PricingUtils.computeEffectiveCostPrice`), so 7.125% off PTR 100 is 92.88. Checkout divides a line's additional disc % and percentage scheme at 6 dp too (`CheckoutService.calculateItemTotalAmount` and `calculateAdditionalDiscountTotal`, `CheckoutUtils.getTaxableLineValue`, `SchemePriceUtils.percentagePrice`, `PurchaseMapper`), as does a return (`SalesReturnValuation`), so the bill matches the rate: one unit at that rate is a 92.88 line, 104.02 with 12% GST. A % with up to 2 decimals gives the same result as before; money is still rounded to 2 dp where it was. The shop's rules are read once per save. `RateGenerationService` builds a batch's rates from the shop's rules, matching by exact rate name, in these cases:

- **New batch** (`PricingService.createAndReturnEntity`, including stock-in): create always generates rule rates from the rules; rule rates sent by the client, with any disc % or scheme they carry, are replaced by generated ones, and no grant is checked. Overrides are made on saved batches only (the pricing screen, `PATCH`).
- **Inventory update** (`PricingService.update`): when the request sends rates or a `defaultRate`, or changes `priceToRetail`, `maximumRetailPrice` or `sellingPrice` (a value differing from the stored one; a base sent unchanged does not count), rule rates are recalculated against the new bases. This path takes no `resetRates`; reset a rate with `PATCH`.
- **`PATCH /api/v1/pricing/{id}` and `POST /api/v1/pricing/bulk-update`:** when the request sends `rates`, `resetRates` or a `defaultRate`. Each rate sent needs a `price`, even for a terms-only override (send the current price; it is ignored for a rule rate). Both writes need the product-search grant for `rates` and `defaultRate`, as sending those fields to `PUT /api/v1/inventory/{id}` does (`RbacModuleInterceptor`): the owner, or a member with edit access when the shop's mode is full edit; else 403 `ACCESS_DENIED`. `GET /api/v1/pricing/{id}` stays open to shop members.
- **A retailer with rules** keeps generated rates on inventory save (`InventoryPricingWriteHandler`) and they are rebuilt from the selling price; an inventory update that sends only the selling price sends it as MRP and PTR too, as create does. A retailer without rules still has no rates and keeps today's edit behaviour. A shop without rules is otherwise untouched.

Rates that match no rule are stored with their name and price only, marked `manual`; any other field sent with them (`base`, `baseValue`, disc %, scheme, `gstMode`) is dropped, so checkout bills them as legacy rates. They need no grant. A shop without rules stores every rate sent the same way, on create, `PATCH` and inventory update. A stored rule rate whose rule is gone (a rule removed, or `PUT` with `rules: []`) is stripped the same way on the batch's next write that would regenerate; until then checkout (`CartRateResolver`, which checks the shop's live rules) bills it as a legacy rate, price only, and does not apply it as a default rule rate. A rule skipped for a batch (base value missing) produces no rate. A rule that takes over a hand-typed rate's name replaces it and the `PATCH` / bulk-update response warns `{name, reason: "REPLACED_BY_RULE"}`. On a saved batch a rate sent whose name differs from a rule's only in case (`rate-a` against `Rate-A`) is 400 (also on an inventory update, which then saves nothing; other pricing failures there are still logged and skipped); so is (on inventory create and update, `PATCH` and bulk-update, rules or not) a rate sent with a built-in price's name (`MRP`, `PTR`, `Cost`, `priceToRetail` ...).

**Overrides.** A rule rate sent with a disc % or scheme that differs from the stored one (or from the rule, if nothing is stored) is an override. It changes the terms only: the base stays the rule's base, the price is recalculated from the rate's own terms (a price sent for a rule rate is ignored), and the rate is marked `manual`, so later base changes recalculate it from its own terms instead of the rule's. A null term means unchanged, and disc 0 equals no disc. An override, or a name in `resetRates` (regenerate from the rule), needs the `rateRulesEdit` grant, else 403 `ACCESS_DENIED` (1003). Disc is validated 0 to 100 and the scheme shape as for a rule (1006). An override of a rate whose base has no value on the batch (an MRP rule on a batch without MRP) is 400 naming the rate. An override is never dropped for being below cost; it is kept, and the response of `PATCH` and bulk-update lists it in `warnings: [{name, reason}]` (an inventory update applies it but returns no `warnings`) (`reason` `BELOW_COST`), together with any rule skipped below cost. `warnings` is null when there are none. Pricing responses (`GET` and `PATCH /api/v1/pricing/{id}`, bulk-update) and inventory reads give each rate its terms (`base`, `baseValue`, `discountPercent`, `scheme`, `manual`, `gstMode`) and a read-only `termsText` (e.g. `PTR 100.00 − 8% · 10+1`); all are null for a legacy rate. Pricing responses also carry `defaultRateOptions`: the names the batch's `defaultRate` may take, `priceToRetail`, `maximumRetailPrice`, `costPrice`, then every rate not built on the selling price and not named like a built-in price (`PricingUtils.defaultRateOptions`, the same selling-price test as the fallback below).

**Below cost.** A generated rate whose price is under the batch cost (`effectiveCostPrice`, falling back to the cost price) is skipped, so no rate is stored for that rule on that batch (`inventory_pricing_rate_rule_skipped_total`, tag `reason=BELOW_COST`). An inclusive price is compared ex-GST, divided by 1 + GST%. The rate-rules preview lists these as skipped.

**GST mode.** A rate's `gstMode` is `INCLUSIVE` when its base equals the batch MRP (the price includes GST, as for any line sold at MRP), otherwise `EXCLUSIVE` (GST on top). How an at-MRP line is billed and kept at MRP is described under "Lines sold at MRP" above.

**Default rate.** A batch's `defaultRate` that names a rate no longer generated (for example skipped below cost), or one built on the selling price (also when it is the only thing a `PATCH` or inventory update sends), falls back to `priceToRetail` and is logged (`inventory_pricing_rate_default_fallback_total`). A named default rate then sets the selling price to that rate's price; `priceToRetail`, `maximumRetailPrice` and `costPrice` as default leave it as it was.

**Checkout with a rate (`rateName`).** `POST` add-to-cart items take an optional `rateName`. A rule rate puts its base on the line with its own disc and scheme (`CartRateResolver`), so Rate-A on PTR 100 goes on as price 100, 8%, 10+1 and bills 920.00 before GST for 11 units -- never the final 83.64 with the terms on top. In an item carrying `rateName` the rate's disc and scheme replace the batch's sale defaults and any disc/scheme sent in the same item, and a scheme the rate does not have is cleared; `rateName` with quantity 0 re-terms a line already in the cart. A legacy rate (name and price only) gives its price and leaves the terms as they were.

- **Per sale unit.** A rate's figures are per pack (the pricing unit, e.g. a strip). Picked on a line sold by another unit (a tablet line), the price goes on per tablet, base / pack rounded down as the per-tablet MRP is (`CheckoutService.perPackToSaleUnit`), with the line MRP for a tablet; disc and scheme apply unchanged, the scheme on the tablets paid for. PTR 100, strip of 10, 110 tablets of Rate-A bill 10.00 x 100 x 0.92 = 920.00 + GST. An at-MRP rate on a strip of 15 at MRP 100 goes on at 6.66 a tablet, at MRP, GST inside. A first add by the tablet, by `rateName` or by the batch's default rule rate, goes on the same way; the line MRP is the tablet's also when a disc or scheme sent with it leaves no applied rate.
- **System options.** `rateName` may name a built-in price (`SystemRateNames`): `priceToRetail`, `maximumRetailPrice` or `costPrice` (also `PTR`, `MRP`, `Cost`, any case). It always means the batch field, never a stored rate of the same name: that field goes on the line (per sale unit, as above). On a line at a rule rate (its `appliedRateName` is a live rule rate) disc and scheme go back to the batch's own sale defaults, replacing the rule's terms. On any other line disc and scheme stay as they are (a typed 5% survives a PTR pick, as before rate rules), and a first add takes disc/scheme sent with it or else the batch's. 400 if the batch has no value for it.
- **Applied rate.** Each cart line stores `appliedRateName` (on `PurchaseItem`, returned on cart responses): the picked rate's name, the system key (`priceToRetail`, `maximumRetailPrice`, `costPrice`) for a system pick, or the batch's default rule rate on a first add. A manual price, disc or scheme edit that changes the line clears it (null), also when sent with a unit switch (a price sent to a line held at MRP is ignored and keeps it); a + that resends the line's own disc, or a switch that sends the line's own price for the new unit, keeps it. Null on lines at the batch's own price and on lines saved before this.
- **Lines keep their own terms.** With no `rateName`, an incoming line's price is always the batch's, and its disc and scheme are the batch's unless the request sent them (`PurchaseItem.batchFilledPrice` / `batchFilledDisc` / `batchFilledScheme`, request-scoped). A line already in the cart keeps its own price, disc and scheme on +/-, scan-again and unit switch unless the request sent them, whatever the batch's default rate. So a picked Rate-B gains no batch 5+1 on +, a picked Rate-A keeps its price, and a manual price edit survives +1 for every shop (before, +1 reset the price to the batch selling price). An at-MRP line is still held at MRP on a quantity change ("Lines sold at MRP").
- **Default rate.** A lot new to the cart, with no `rateName`, whose batch `defaultRate` is a rule rate gets the rate's base, disc and scheme in place of the batch's sale defaults (disc/scheme sent with the item still win, and then the line records no applied rate). The default rate never re-terms a line already in the cart.
- **No unit sent.** An item with no `unit` for a lot already in the cart is for that line's unit: a quantity-0 pick or edit on a tablet line prices the tablet line rather than switching it to strips. A lot new to the cart with no `unit` takes the batch's pack unit, as before.
- **Errors.** `rateName` with a unit change (switch the unit, then pick), or with a quantity decrease (a "-"), is 400 `VALIDATION_ERROR`. An unknown `rateName` is 400. `rateName` with quantity 0 for a lot not in the cart is 404.

GST then follows the line: inclusive when its price is its MRP, on top otherwise, which is the rate's `gstMode`.

### Dot-matrix printing (documentservice)

A dot-matrix printer cannot take a PDF, so these documents are served as fixed-width plain text. The shop's print bridge sends the text to the printer and owns the ESC/P control bytes and where the paper sits. The backend owns the layout and every figure on it.

| Endpoint | Returns |
|----------|---------|
| `GET /api/v1/invoices/{purchaseId}/dot-matrix` | The sale invoice, or the estimate for a `BASIC` sale |
| `GET /api/v1/refund/{refundId}/dot-matrix` | The customer credit note |
| `GET /api/v1/vendor-purchase-returns/{returnId}/dot-matrix` | The vendor debit note |

All three are `text/plain;charset=UTF-8`, rendered by `InvoiceTextRenderer` (`core/documentservice/.../service`). An estimate is 80 columns. A tax invoice is 137 columns condensed (8 inches): the masthead, then the buyer, tax-id and document columns (45 + 44 + 46), then the items, then one GST row per rate (`taxRateRows`). On an interstate bill that row is IGST at the combined rate (`igstPercent`), never SGST and CGST. Round-off is signed by direction.

The in-app preview for a dot-matrix printer (`DotMatrixInvoicePreviewRenderer`) shows the same text rather than a separate template, so the preview and the paper cannot disagree. Laser (`NormalInvoicePreviewRenderer`) and thermal (`Thermal3InchInvoicePreviewRenderer`) are previewed from their Thymeleaf templates, through the shared `AbstractTemplateInvoicePreviewRenderer`. Each printer type has exactly one renderer (`InvoicePreviewRenderer.supports`); a printer type without one fails rather than borrowing another printer's template.

The shop's invoice settings decide which columns print. `showPack` turns the PACK column off.

**Print jobs: the backend decides, the browser carries.** Printing on dot matrix goes through three endpoints in `core/product` (`service/printing/`). The browser probes the bridge (only a page on the shop PC can reach `127.0.0.1:9110`), reports what it saw, and does what it is told.

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/print-bridge/connection` | Where the browser reaches the bridge and how long it waits: `origin` (loopback only; the app refuses to start otherwise), `healthTimeoutMs` (for `GET /health` and `GET /jobs`), `printTimeoutMs` (for `POST /print`). Read before the first probe. |
| `POST /api/v1/print-bridge/status` | Body: what the probe saw (`reachable`, `version`, `selectedPrinter`). Returns `state` (`NOT_DETECTED`, `OUTDATED`, `CONNECTED`), `latestVersion`, `minimumVersion`, `downloadUrl`. An outdated bridge still prints. |
| `POST /api/v1/print-jobs` | Body: `source` (`SALE`, `REFUND`, `VENDOR_RETURN`), `documentId`, `bridge` (the probe), `pendingAgeMs` (how long ago this browser sent the same document without seeing it finish; null if it has not). Renders the document and returns `action`: `BRIDGE` (send `bridgeRequest` to the bridge unchanged), `DOWNLOAD` (no bridge: save `download`, a `.prn` file) or `IN_PROGRESS` (`pendingAgeMs` is within `inFlightTtlMs`; send nothing). Also `reason`, `documentKind` (`INVOICE`, `ESTIMATE`, `CREDIT_NOTE`, `DEBIT_NOTE`, decided by the same rule that labels the paper), `documentNumber`, `poll` (`intervalMs`, `budgetMs`) and `inFlightTtlMs`. |
| `POST /api/v1/print-jobs/outcome` | Body: `source`, `observation` (`PRINTED`, `FAILED`, `STILL_QUEUED`, `UNREACHABLE`, `DUPLICATE`, `REJECTED`, `EXPIRED`), `bridgeJobId`, `error`. Returns `outcome` as a code (`EXPIRED` gives `UNCONFIRMED`) plus `shouldClose`, `retryable` and `downloadInstead`. The frontend words the code. |

`bridgeRequest` asks the bridge for its own configured copies (`copies: 0`). Nothing is stored: the bridge holds the job, and the browser keeps what it sent in `localStorage` for `print-bridge.in-flight-timeout` (2 minutes, sent as `inFlightTtlMs`) and reports it as `pendingAgeMs`. A job the browser never saw finish within that time is reported `EXPIRED` and comes back `UNCONFIRMED`, which means outcome unknown, not "did not print". Two tabs racing within milliseconds are caught by the bridge's own duplicate check (409, `ALREADY_SENT`); two different PCs are not deduplicated. Physical printing is at-least-once: a refusal from a bridge that was reached (`BRIDGE_REJECTED`) offers no fallback file, because the job may already have printed.

The bridge offered to shops is set per environment: `PRINT_BRIDGE_ORIGIN` (default `http://127.0.0.1:9110`), `PRINT_BRIDGE_HEALTH_TIMEOUT_MS` (default 1200), `PRINT_BRIDGE_PRINT_TIMEOUT_MS` (default 8000), `PRINT_BRIDGE_DOWNLOAD_URL`, `PRINT_BRIDGE_LATEST_VERSION` (default `0.12.0`) and `PRINT_BRIDGE_MINIMUM_VERSION` (default `0.11.0`, the first release that gives an estimate its own page length). The default download URL is the release on Google Drive; it must be shared as "Anyone with the link", or a shop that is not signed in to that Google account cannot download it.

### Shop grid layouts (product)

The product entry grid view lays out its columns the way a spreadsheet does: column order, widths and hidden columns belong to the **shop**, so every member and every device sees the same layout. Scroll position and how many columns fit on screen stay with each browser. Only an **owner or admin** may change the layout (`RbacService.canManageShopLayouts`); everyone else uses it. The list view uses the same hidden columns to hide optional fields (no widths or order there).

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/shops/active-shop/grid-layouts/{gridId}` | The saved layout or the default, plus `catalog` (every column the grid can show, its display `label` and its rules), `canEdit` for the caller, and `revision` (null on the default). |
| `PATCH /api/v1/shops/active-shop/grid-layouts/{gridId}` | One change: `{ type: "RESIZE", key, widthPx }`, `{ type: "SET_HIDDEN", key, hidden }` or `{ type: "MOVE", key, beforeKey }` (`beforeKey` null = last movable). 400 when it breaks a column rule, 403 for a non-admin. |
| `DELETE /api/v1/shops/active-shop/grid-layouts/{gridId}` | Reset to the default (owner or admin). |

**Column rules are separate from field rules.** Each catalog entry carries `required` (must be filled in to save: from the vertical schema, or a core stock-in field), and, for layout only, `hideable`, `reorderable` and `position` (`FIRST`, `ANY`, `LAST`). A required column cannot be hidden but can move; `rowNo` is `FIRST` and `actions` is `LAST`, and neither moves nor hides. The catalog (`GridCatalogs` looks up the `GridCatalog` for the grid; product entry is `ProductEntryGridCatalog`, `core/product/.../gridlayout`) lists every column any billing mode can show; vertical schema fields appear as `field:<schemaKey>`, so a new vertical gets its columns with no grid code.

**Another screen.** The layout API, storage, concurrency and permissions are generic over `gridId`. To give another grid a shop-shared layout, add its id to `GridIds` and a Spring bean implementing `GridCatalog` (its columns, in default order; `SchemaGridColumns` turns vertical schema fields into columns). Nothing else on the backend changes.

**Concurrent changes.** A change is applied to the latest saved layout under an optimistic lock (`@Version revision` on `shop_grid_layouts`); if another member saved in between, it is re-applied on top of theirs (up to 3 times, then 409). Changes to different columns both survive; two changes to the same column: the later one wins. Other members' browsers pick the new layout up when they refetch (window focus, after their own change).

**Schema changes.** Stored keys only, one document per shop and grid; the unique `(shopId, gridId)` index is created at startup by `GridLayoutIndexMigration` (automatic index creation is off). On read, unknown keys are dropped, widths are clamped, required columns are shown and new columns are placed after their catalog neighbour (`GridLayoutResolver`), so a shop keeps its layout across schema versions.

### Stockkart staff (SUPER_USER, user)

`SUPER_USER` is a platform role for internal Stockkart staff, not a shop role. A SUPER_USER account has no shop (`shopId` null) and no shop memberships. Stockkart HQ signs these accounts in through `POST /api/v1/auth/login` and admits only `user.role == "SUPER_USER"`.

- No API grants it: signup, invitations, join requests and shop-member role changes reject `SUPER_USER` (400). Invitations, join requests, shop registration and membership creation also reject a SUPER_USER account (400).
- Shop RBAC denies a SUPER_USER account (`RbacService`, 403), so it cannot act inside any shop. `UserShopMembershipService.hasAccess`/`hasOwnerAccess` are false for it, so an `X-Shop-Id` header from staff is ignored.
- Only the operator script `scripts/super-user.mongodb.js` grants or revokes it; see [docs/deploying.md](docs/deploying.md#granting-or-revoking-super_user).

| Endpoint | Who |
|----------|-----|
| `POST /api/v1/auth/login`, `GET /api/v1/auth/me` | Any account; return `role` (`SUPER_USER` for staff) and work with no shop. |
| `POST /api/v1/auth/change-password` | Signed-in account only (Bearer token); body `{ currentPassword, password }`; changes the token's account and logs out its other sessions (the caller's token stays valid). Not public. |
| `POST /api/v1/shops/admin/shops/{shopId}/approve` | SUPER_USER only; anyone else gets 403. Body `{ approve, userLimit }` (`userLimit` > 0 to approve). |

### Worker accounts (user)

`WORKER` is the role of an account created from the *Stockkart Team* app. Its one shop is `UserAccount.shopId`, written by HR when a manager approves the worker's join request (`WorkerAccountService.claimShop`, one conditional update, so two shops cannot both win) and cleared on exit or "Leave this shop" (`releaseShop`, which also ends the `WORKER` membership). `GET /api/v1/shops/{shopId}/users` rows now carry `email`, and `linkedEmployeeCode` for `WORKER` rows, so the Users page can group workers below the staff; workers are not plan seats (`staffCountForShop`). Index `worker_shopId` (partial, `role = WORKER`) is ensured by `UserTokenIndexMigration`.

Sign-up and sign-in are the existing `POST /api/v1/auth/signup` and `POST /api/v1/auth/login`, with two additive fields: `role: "WORKER"` on sign-up (the only way to get the role: add-user, update-role, invitations and staff join requests refuse it with "WORKER is set only by approving a worker's join request"), and `source` (`"web"` default, `"worker-app"`) on both, which tags `inventory_user_auth_total{source}` and the welcome / login e-mail. A WORKER sign-up needs `phone` (a 10-digit Indian mobile) on the password and the Google path alike, and starts with `shopId = null`, no membership and no employee. A `source = worker-app` sign-up is admitted by HR's per-IP limit first (20 per address per rolling hour, `429 HR_TOO_MANY_ATTEMPTS` "Too many attempts, try again later", nothing read or written). Google ID tokens are accepted only for the client ids in `GOOGLE_OAUTH_CLIENT_IDS`. A Google login whose e-mail has no account answers `401 OAUTH_ACCOUNT_NOT_FOUND` (4004) "No account found for this Google account. Please sign up first." (the text the web always showed; the app matches the code and opens its phone step with the same token instead of showing it), while a wrong password and a token for another client stay `INVALID_CREDENTIALS`. Everything the worker does *with* the login is under `/api/v1/hr/worker/**`; see [Stockkart Team (worker-app)](#stockkart-team-worker-app).

| Endpoint | Who | Notes |
|----------|-----|-------|
| `PUT /api/v1/users/me/profile` | Bearer, own account, any role, any shop state | `{ name?, phone }`: phone required, a 10-digit Indian mobile (`+91`, spaces, hyphens stripped); name optional, 2–100 characters after trimming when given, **absent or blank = unchanged** (the Stockkart Team app's "Add your phone number" step sends the phone alone). A blank or invalid phone is 400 and changes nothing. The e-mail cannot be changed. Returns the account |
| `PUT /api/v1/users/me/photo` | Bearer, own account | `multipart/form-data`, one part `photo`: JPEG or PNG, ≤ 300 KB, ≤ 512 × 512 pixels, decoded to prove it is an image; anything else is 400, nothing is resized. One photo per account (`user_photos`). Returns `{ photoUrl, contentType, bytes, updatedAt }` |
| `DELETE /api/v1/users/me/photo` | Bearer, own account | Removes the photo |
| `GET /api/v1/users/{userId}/photo` | Bearer; the account itself, an approver of a shop where it has a Pending or recently Rejected join request (HR decides), or an active member of a shop it is an active member of | Image bytes (`image/jpeg` or `image/png`), `Cache-Control: private, max-age=300`; `me` = the caller. 403 before 404, so a stranger learns nothing. Never a public URL |

### HR (hr)

Employees, attendance, leave, salary advances, payroll, salary payments, payslips and HR registers (`core/hr`, spec `.kiro/specs/hr-module`). Every path needs a Bearer token except the Attendance app device paths listed in the second table below, which are authenticated by a per-phone signature instead. Staff paths sit behind the RBAC module `hr` (member switch `modules.hr`, `null` = on) and an action permission from the shop's HR role grid (owner: all); `/api/v1/hr/me/**` and `/api/v1/hr/worker/**` skip the module check and work only on the caller's own linked employee (`404 HR_NOT_LINKED` when the login has none, except notifications; the worker paths have their own rule, below). Shop and caller always come from the request attributes. Pay amounts and full bank/UPI details need `VIEW_SALARIES`. Endpoints marked *heavy* run under the heavy-request limiter kind `hr` (2 at once, 4 waiting, 10 s, then 503 with `Retry-After`). Plan expiry blocks HR like the rest of the app, except `/api/v1/hr/me/payslips/**`, `/api/v1/hr/worker/**` and the Attendance app device paths.

| Endpoint | Permission | Purpose |
|----------|------------|---------|
| `GET /api/v1/hr/settings`, `PUT /api/v1/hr/settings`, `GET /api/v1/hr/setup-status` | any HR permission; PUT `MANAGE_SETTINGS` | Weekly off (with `effectiveFrom`), hours, grace, half-day, overtime, per-day basis, leave-year start; `attendanceCapture.biometricApp` (the Biometric app check-in switch, default off, read on every app punch) and `pairedEmployeeCount` in the response |
| `GET`, `PUT /api/v1/hr/role-permissions` | owner | Role grid, closed under the dependency rules on save |
| `GET`, `POST /api/v1/hr/holidays`, `PUT`, `DELETE /api/v1/hr/holidays/{id}` | GET any HR; writes `MANAGE_SETTINGS` | Holidays per year |
| `GET`, `POST /api/v1/hr/leave-types`, `PUT /api/v1/hr/leave-types/{id}` | GET any HR; writes `MANAGE_SETTINGS` | Leave types (three seeded defaults) |
| `GET`, `POST /api/v1/hr/employees`, `GET`, `PUT /api/v1/hr/employees/{id}`, `POST /api/v1/hr/employees/{id}/salary-revisions`, `POST /api/v1/hr/employees/{id}/exit`, `GET /api/v1/hr/employees/designations`, `GET /api/v1/hr/linkable-logins` | `VIEW_EMPLOYEES` / `ADD_EDIT_EMPLOYEES` (+ `VIEW_SALARIES` for pay) | Employees, masked without `VIEW_SALARIES` |
| `POST /api/v1/hr/employees/{id}/request-profile` | `ADD_EDIT_EMPLOYEES` | "Request profile details" (worker-app R8.7): stamps `profileRequestedAt` (now on `EmployeeResponse`), writes a `PROFILE_REQUESTED` audit row and a `PROFILE_REQUESTED` notification to the linked worker account, which shows "Your shop has asked for your details" in the app. No body; returns the employee |
| `GET /api/v1/hr/attendance/daily?asOf=`, `PUT /api/v1/hr/attendance/days` (≤ 500 rows), `POST /api/v1/hr/attendance/mark-unmarked-present`, `GET /api/v1/hr/attendance/month?period=&employeeId=`, `GET /api/v1/hr/attendance/history?employeeId=&date=` | `VIEW_ATTENDANCE` / `MARK_ATTENDANCE` | Daily and month registers, bulk save, history with actor names. Rows carry `punchSource`, `markReasonRequired`, `noPunchYet`; a hand-made mark takes `reason` (`FORGOT_PHONE`, `PHONE_NOT_WORKING`, `NO_NETWORK`, `BIOMETRIC_NOT_WORKING`, `OTHER` + `reasonText` 1–200 chars), required when Biometric app check-in is on and the employee has a paired phone |
| `GET /api/v1/hr/employees/{id}/pairing`, `POST /api/v1/hr/employees/{id}/pairing-codes`, `POST /api/v1/hr/employees/{id}/pairing/unpair` | `ADD_EDIT_EMPLOYEES` | Attendance app pairing for one employee: state (`NOT_PAIRED` \| `PAIRED` with phone model, since, last punch, `attested` true \| false \| null), a 15-minute one-time code as QR payload + 8 characters (cancels the previous unused code), unpair now. `GET /api/v1/hr/employees?paired=true\|false` filters the list by active pairing |
| `GET /api/v1/hr/attendance-app/health` | `VIEW_ATTENDANCE` (Bearer chain; the one `/attendance-app` path that is **not** a device path) | Open signature-failure alerts `{alerts: [{shopId, rate, failed, attempts, raisedAt}]}` for the HR dashboard panel; an alert opens when a shop's rate exceeds 1 % over 60 minutes with ≥ 20 attempts and clears after a full window at or below 1 % |
| `GET /api/v1/hr/attendance/month/export?period=` *heavy* | `VIEW_ATTENDANCE` | Month attendance register (xlsx) |
| `GET`, `POST /api/v1/hr/leave-requests`, `GET /api/v1/hr/leave-requests/preview`, `POST /api/v1/hr/leave-requests/{id}/approve`, `/reject`, `/cancel` | `VIEW_ATTENDANCE`, `MARK_ATTENDANCE`, `APPROVE_LEAVE` | Leave requests (with `leaveTypeName`) |
| `GET /api/v1/hr/leave-balances?year=&employeeId=` | `VIEW_ATTENDANCE` | Prorated quota, used, available per type; one employee with `employeeId` |
| `GET /api/v1/hr/leave-balances/export?year=` *heavy* | `VIEW_ATTENDANCE` | Leave balance list (xlsx) |
| `GET`, `POST /api/v1/hr/advances`, `POST /api/v1/hr/advances/{id}/cancel`, `GET /api/v1/hr/employees/{id}/advance-statement` | `MANAGE_ADVANCES` | Salary advances (posted to 1250) and statement |
| `GET /api/v1/hr/payroll-runs`, `GET /api/v1/hr/payroll-runs/{runId}`, `GET /api/v1/hr/payroll-runs/{runId}/unmarked` | `RUN_PAYROLL` or `RECORD_PAYMENTS` | Runs with lines; who still has unmarked days |
| `POST /api/v1/hr/payroll-runs` *heavy*, `POST /api/v1/hr/payroll-runs/{runId}/recalculate` *heavy*, `PUT /api/v1/hr/payroll-runs/{runId}/lines/{lineId}/adjustments` | `RUN_PAYROLL` | Draft for a month, recalculation, manual items and advance override |
| `POST /api/v1/hr/payroll-runs/{runId}/finalise` *heavy*, `POST /api/v1/hr/payroll-runs/{runId}/reopen` | `RUN_PAYROLL` | Finalise (oldest month first, one `PAYROLL_RUN` journal) and reopen with a reason (newest first, refused while anything is paid) |
| `POST /api/v1/hr/payroll-runs/{runId}/payments` | `RECORD_PAYMENTS` | `{lineIds[] ≤ 50 \| all: true, date, method CASH\|BANK\|UPI, reference?}`: one batch, one `SALARY_PAYMENT` journal; per-line status |
| `POST /api/v1/hr/payroll-runs/{runId}/lines/{lineId}/payment/undo` | `RECORD_PAYMENTS` | `{reason?}`: compensating journal for that line only, line back to pending |
| `GET /api/v1/hr/payroll-runs/{runId}/lines/{lineId}/payslip` | `VIEW_SALARIES` | Payslip PDF of a finalised run |
| `POST /api/v1/hr/payroll-runs/{runId}/payslips/email` | `RUN_PAYROLL` | `{lineIds[] ≤ 300}` → `{queued, skippedNoEmail[]}`; the outbox renders each PDF when it sends (10 per tick) |
| `GET /api/v1/hr/payroll-runs/{runId}/register?format=xlsx\|pdf` *heavy* | `VIEW_SALARIES` | Salary register |
| `GET /api/v1/hr/me`, `GET /api/v1/hr/me/attendance?period=`, `GET /api/v1/hr/me/leave?year=`, `GET /api/v1/hr/me/leave/preview` | linked employee | Own profile (own pay shown, bank/UPI masked), month, balances and requests |
| `POST /api/v1/hr/me/leave-requests`, `POST /api/v1/hr/me/leave-requests/{id}/cancel` | linked employee | Request or cancel own leave |
| `GET /api/v1/hr/me/payslips`, `GET /api/v1/hr/me/payslips/{lineId}/pdf` | linked employee (allowed while the plan is expired) | Own payslips of finalised months; another employee's line is 404 |
| `GET /api/v1/hr/me/notifications?since=`, `POST /api/v1/hr/me/notifications/{id}/read`, `POST /api/v1/hr/me/notifications/read-all` | any shop member | HR inbox (polled every 60 s) |
| `GET /api/v1/hr/me/check-in`, `POST /api/v1/hr/me/check-in` | linked employee with `SELF_CHECK_IN` | My HR self check-in: today's state and button label; record a punch (source `SELF`) under the shop's Location check |
| `GET /api/v1/hr/me/pairing`, `POST /api/v1/hr/me/pairing-codes` | linked employee | Pair the caller's own phone to the Attendance app: same shapes as the manager endpoints above, `pairedBy = "self"`; unpairing from My HR is not offered (the worker uses the app's "Remove this phone") |

#### Attendance app device paths (`/api/v1/hr/attendance-app/**`, `AttendanceAppController`)

A punch never needs an account (worker-app R9.10), so these paths are outside the Bearer, RBAC-module and plan-expiry interceptors (`CorsConfig`, `AuthenticationInterceptor.PUBLIC_ENDPOINT_PATTERNS`, listed one by one as `AttendanceAppAuthInterceptor.DEVICE_PATH_PATTERNS`: `/me`, `/punches`, `/consent/**`, `/pairings/me/**`) and are guarded by `AttendanceAppAuthInterceptor`, registered on exactly that list. Every call except `GET /consent/latest` carries a **signed request envelope**: headers `X-Pairing-Id`, `X-Request-Timestamp` (epoch seconds, within ±10 min of server time), `X-Request-Nonce` (UUID, single use for 15 min) and `X-Request-Signature` (Base64 DER ECDSA P-256/SHA-256 over the canonical `SKAT-REQ-1` lines, made with the pairing's non-exportable *device key*). The interceptor resolves the pairing (`ACTIVE` only), sets `pairingId`, `shopId`, `employeeId` as request attributes (the controller never reads them from the body) and adds `X-Server-Time` to every response. A failed envelope is `403 HR_APP_NOT_PAIRED` when the pairing is unpaired or unknown, else `422 HR_APP_NOT_VERIFIED`; both count as `signature_failed`. A device request that also carries an `Authorization` header is `400 HR_TWO_CREDENTIALS` before any handler and counts as `signature_failed` too (worker-app R9.1: a login and a phone signature never travel together). There is no shop-wide or app-wide secret; the server holds public keys only. `GET /api/v1/hr/attendance-app/health` is **not** a device path and stays on the Bearer chain (table above). There is **no pairing handshake here** (worker-app D11, DD4): a pairing code is redeemed only on `/api/v1/hr/worker/pairings/claim|complete|abandon` with the Bearer token, by a signed-in worker for their own employee record (below). The old unsigned `/attendance-app/pairings/claim|complete|abandon` handlers are gone; those paths are ordinary unknown `/api` routes (401 without a token, 404 with one) and read or count nothing.

| Endpoint | Auth | Purpose |
|----------|------|---------|
| `GET /api/v1/hr/attendance-app/me` | signed envelope | Home screen: names, today, `biometricApp`, `canPunch`, `nextDirection`, today's in/out, the shop's Location check settings (coordinates go to a paired phone only, so the phone can compute its distance), consent state |
| `POST /api/v1/hr/attendance-app/punches` | signed envelope; 20 per pairing per rolling 60 s (`429 HR_TOO_MANY_ATTEMPTS`) | The ten canonical `SKAT-PUNCH-1` fields + `signature` made with the biometric-gated *punch key* → `{outcome STORED \| DUPLICATE \| REPLAYED, direction, at, atLocal, flags[], todayIn?, todayOut?, nextDirection}`. Same refusal order and messages as My HR (`HR_APP_OFF`, not employed, on leave, locked, location); offline punches (`offline=1`) use the phone time, must be ≤ 24 h old and ≤ 5 min ahead (`HR_APP_TOO_LATE`, `HR_APP_CLOCK_WRONG`) and get the flag `RECORDED_OFFLINE`; a mock location is `HR_FAKE_LOCATION` or the flag `FAKE_LOCATION`. Dedupe key `B:<employeeId>:<punchId>`: a resend answers the stored result, a resend with different content is `HR_APP_NOT_VERIFIED` |
| `GET /api/v1/hr/attendance-app/punches?from=&to=` | signed envelope | The caller's own punches in a window of ≤ 31 days, newest first, with `counted`, `flags`, `source` |
| `POST /api/v1/hr/attendance-app/consent` | signed envelope | `{version}` → `{agreedVersion, at}`: agree to a newer consent text (re-consent) |
| `GET /api/v1/hr/attendance-app/consent/latest` | none (carries no shop data) | The current consent text and version; also the source of the privacy note shown from HR Settings |
| `POST /api/v1/hr/attendance-app/pairings/me/unpair` | signed envelope | "Remove this phone": the pairing becomes `UNPAIRED` (reason `WORKER`), the phone then wipes its storage |

**Key attestation (task 16.4, recorded only).** An Android phone may add `attestationChain` to `complete`: the key attestation certificate chain of its punch key (Base64 DER X.509, leaf first), generated with the claim token as `setAttestationChallenge`. `KeyAttestation` (`core/hr/utils`, no library: `java.security` for certificates and signatures, a small DER walker for the extension) checks that every certificate is signed by the next and the chain ends in one of the embedded Google Hardware Attestation Root keys (RSA-4096 root of 2016/2022 and the EC P-384 "Key Attestation CA1" that signs from 2026-02; `KeyAttestation.GOOGLE_ROOTS`, extend the list when Google publishes another); that the first key-description extension counted from the root says `attestationSecurityLevel` is `TrustedEnvironment` or `StrongBox`; that the signed challenge is the claim token; that the hardware-enforced authorisation list has `purpose` ∋ SIGN, `algorithm` EC, `ecCurve` P-256, a strong biometric in `userAuthType` with no `noAuthRequired`, and `authTimeout` absent, 0 or ≤ 30 s; and that the attested public key is the `punchPublicKey` being registered. The verdict goes on the pairing (`attested` true/false/null, `attestationSecurityLevel`, `attestationFailureReason`), into the `DEVICE_PAIRED` / `DEVICE_REPLACED` audit row (`attested`, never the chain) and into `inventory_hr_app_pairings_total{event="attestation", reason=ok|<check>}`. iOS and older app builds send no chain and nothing changes for them. **Not enforced yet**: a failed or missing attestation never refuses a pairing; turning the verdict into a refusal (with a per-shop or global switch, and a grace period for already-paired phones) is a later version. Not checked in this version: certificate validity dates, Google's revocation status list, the provisioning-information extension, and iOS (App Attest).

**Load test (task 16.2, R10.9–R10.10).** `perf/attendance-app-punches.js` (k6 ≥ 1.0) drives 100 signed punches/s for 10 minutes plus `me`, history and pairing traffic against a seeded throwaway environment (`perf/seed-attendance-app.mjs`) and reads the pass/fail from the server's own `inventory_http_latency_bucket_total` histogram, not from k6's client-side timers. How to seed, run and read it: [`perf/README.md`](perf/README.md).

#### Stockkart Team (worker-app)

The Stockkart Team app (spec `.kiro/specs/worker-app`) gives each worker a login (`WORKER` role, [Worker accounts](#worker-accounts-user)). A worker asks to join a shop by the owner's e-mail, a manager approves from the Employees page, and approval creates the employee from the sign-up details and links it to the login. Biometric check-in still needs the manager's pairing code, which must now be for the signed-in worker's own record. Two credentials exist and never travel together (`400 HR_TWO_CREDENTIALS` on either side): the **Bearer token** for everything the worker does with the login, and the **device envelope** for punches. A pairing code is redeemed only on `/hr/worker/pairings/*` with the Bearer token; a punch never needs an account session (R9.10): its outcome is the same whether the phone's session is live, expired or logged out. Nothing is in production, so there is no flow for a phone paired before accounts existed (D11).

**Worker endpoints (`/api/v1/hr/worker/**`, `WorkerController`).** Bearer token; `RbacModuleInterceptor` skips the prefix (a WORKER membership has every module off, HR included) and `PlanExpiryInterceptor` exempts it, so Home, state, history and profile keep working while the shop's plan is expired. The employee is always the token's: no handler reads an employee id from the request. A request carrying `X-Pairing-Id` or `X-Request-Signature` is refused by `WorkerCredentialInterceptor` before the token is read. Any role may call `GET /me`; a staff account (OWNER/ADMIN/MANAGER/CASHIER) with no linked ACTIVE employee gets `STAFF_NO_EMPLOYEE` there and `403 HR_WORKER_ONLY` everywhere else. Rate limits are per backend instance, in memory (`AppRateLimiter`): `lookup:<userId>` 10/h, `join:<userId>` 5/24 h, `signup-ip:<ip>` 20/h; a refusal is `429 HR_TOO_MANY_ATTEMPTS` "Too many attempts, try again later" and reads or writes nothing.

| Endpoint | Request | Response | Notes |
|----------|---------|----------|-------|
| `GET /api/v1/hr/worker/me` | — | `WorkerStateResponse {state NO_SHOP \| PENDING \| ACTIVE \| STAFF_NO_EMPLOYEE, account, request?, lastOutcome?, shop?, employee?, modules[], pairing?, today?, notifications {unread, top ≤ 3}, serverTime}` | The one answer the app routes on, in this order: a linked ACTIVE employee (WORKER or staff) → `ACTIVE`; else a Pending request → `PENDING`; else a staff role → `STAFF_NO_EMPLOYEE`; else `NO_SHOP`. `modules` is `["ATTENDANCE","HISTORY","PROFILE"]` (`WorkerModules.forShop`) when `ACTIVE`, `[]` otherwise; `lastOutcome` is the last rejected or withdrawn request within 90 days, `NO_SHOP` only; `today` comes from today's punches. Polled every 60 s while waiting and on every foreground |
| `GET /api/v1/hr/worker/shops?ownerEmail=` | ≤ 254 chars | `{shops: [{shopId, shopName, ownerFirstName}]}` | 10/h per account. Every miss (no such account, not an owner, owner with no shop) is the same empty list, so the lookup never says whether an e-mail is a Stockkart account |
| `POST /api/v1/hr/worker/join-requests` | `{shopId}` | `JoinRequestSelfResponse {id, shopName, status, requestedAt}` | 5/24 h. Refused with `HR_WORKER_ONLY` for a staff account, `HR_WORKER_HAS_SHOP` when the account has a shop or a linked ACTIVE employee anywhere, `HR_WORKER_REQUEST_PENDING` when one is already open (also enforced by a partial unique index on `hr_join_requests(userId)` where `status in [PENDING, APPROVING]`). Inserts `PENDING` with a snapshot of the account as it is now (`name, phone, email, hasPhoto`; never updated), notifies the shop's `ADD_EDIT_EMPLOYEES` holders (`JOIN_REQUEST_RECEIVED`) |
| `POST /api/v1/hr/worker/join-requests/{id}/withdraw` | — | `JoinRequestSelfResponse` | Own request, Pending only (one conditional update); otherwise `HR_REQUEST_ALREADY_HANDLED`; 404 for someone else's |
| `POST /api/v1/hr/worker/pairings/claim`, `/complete`, `/abandon` | the device-path bodies | `ClaimPairingResponse`, `PairingBootstrap`, `{abandoned: true}` | The same `DevicePairingService` handshake, lockout and key rules as the device path, plus one rule: the code's employee must be the caller's linked ACTIVE employee (`HR_WORKER_NOT_ACTIVE` when the caller has none), else `403 HR_PAIRING_WRONG_EMPLOYEE` — the code stays valid for the right worker, one attempt is counted on it and a `PAIRING_CODE_REFUSED` audit row names the caller. A self-pairing code made from My HR is accepted only by the account that made it. `employeeName` / `shopName` in the claim answer are the caller's own |
| `GET /api/v1/hr/worker/punches?from=&to=` | ISO dates, ≤ 31 days (default: the last 31 including today) | `PunchResponse[]` newest first | The History module. Employee from the token; includes punches made before the login was linked (same employee) |
| `GET /api/v1/hr/worker/profile` | — | `WorkerProfileResponse {name, phone, email, photoUrl?, shopName?, employeeCode?, joinDate?, profileRequested, pairingState}` | No pay, bank or UPI field exists on this DTO |
| `PUT /api/v1/hr/worker/profile` | `{name, phone}` | `WorkerProfileResponse` | Name 2–100 after trim, phone a 10-digit Indian mobile. Writes the account (`WorkerAccountService.updateProfile`) and, when one exists, the linked employee's name and phone with actor `self` and one `EMPLOYEE_CHANGED` audit row carrying before/after |
| `POST /api/v1/hr/worker/leave` | — | `WorkerStateResponse` | "Leave this shop": exits the caller's own employee today (`EXITED`, `exitDate = today` Asia/Kolkata, actor `self`), which releases the account's shop and ends the `WORKER` membership at once; answers the new state (`NO_SHOP`), after which a new join request is allowed |
| `GET /api/v1/hr/worker/notifications` | — | `{items: HrNotificationResponse[] ≤ 20, unread}` | The account's rows across shops (a worker with a pending request has no shop yet), unread first then newest; rows carry `joinRequestId` |
| `POST /api/v1/hr/worker/notifications/{id}/read` | — | `{read: true}` | Own rows only |

**Manager inbox (`/api/v1/hr/join-requests/**`, `JoinRequestInboxController`).** Bearer token, RBAC module `hr` on (not skipped), `ADD_EDIT_EMPLOYEES` in every handler, plan expiry applies like every staff screen. A `WORKER` token is 403 here (its membership has no HR module). Decisions keep their own code and text and leave the request Pending when refused. Every decision writes one HR audit row (`JOIN_REQUEST_APPROVED` with the outcome, mode and employee id; `JOIN_REQUEST_REJECTED` with the reason) and one notification to the worker (`JOIN_REQUEST_APPROVED` whichever mode was used, R10.2; `JOIN_REQUEST_REJECTED`; texts carry the shop name and the reason, never the phone or e-mail). The bell shows `JOIN_REQUEST_RECEIVED` rows to `ADD_EDIT_EMPLOYEES` holders. Decided requests are kept 90 days (`expiresAt` TTL), then removed with their snapshot.

| Endpoint | Request | Response | Notes |
|----------|---------|----------|-------|
| `GET /api/v1/hr/join-requests?status=PENDING\|REJECTED&page=&size=` | `status` default `PENDING`; `size` ≤ 200 | `JoinRequestsPageResponse {items: JoinRequestRow[], pendingCount, page, size, totalItems, totalPages}` | Newest first. `JoinRequestRow = {id, name, phone, email, photoUrl?, requestedAt, status, hasPossibleMatch}` and nothing else; `photoUrl` is the Bearer-protected `/api/v1/users/{userId}/photo`. `pendingCount` is the tab label |
| `GET /api/v1/hr/join-requests/{id}` | 24-hex id | `JoinRequestDetail = JoinRequestRow + possibleMatch? {employeeId, code, name, status, linked}` | The possible match is a shop employee (ACTIVE or EXITED) whose e-mail equals the snapshot's case-insensitively or whose phone equals it after `PhoneNumbers.normalise`; `linked` = it already has a login |
| `POST /api/v1/hr/join-requests/{id}/approve` | `{mode: CREATE \| LINK, employeeId?}` (`employeeId` required with `LINK`) | `JoinRequestDecision {id, outcome APPROVED_CREATED \| APPROVED_LINKED, employeeId}` | One MongoDB transaction (`MongoTx.WORKER_JOIN_APPROVE`): lock `PENDING → APPROVING` (else `HR_REQUEST_ALREADY_HANDLED`) → `WorkerAccountService.claimShop` (another shop holds the account → `HR_WORKER_LOGIN_IN_OTHER_SHOP`, rollback) → `CREATE`: a new ACTIVE employee from the snapshot, `joinDate` today, auto code, `userId` = the worker; `LINK`: `employeeId`'s `userId` set (`HR_MATCH_LINKED_ELSEWHERE` when it has another login, `HR_MATCH_EXITED` when exited) → a `WORKER` membership when the account is WORKER (a staff account keeps its own) → `APPROVED`. Any failure rolls back and the request is Pending again; audit, notification and counter run after commit |
| `POST /api/v1/hr/join-requests/{id}/reject` | `{reason?}` | `JoinRequestDecision {id, outcome REJECTED}` | Reason trimmed; blank → none; over 200 characters → 400 "The reason must be at most 200 characters". Pending only, else `HR_REQUEST_ALREADY_HANDLED` |
| `POST /api/v1/hr/join-requests/bulk/approve` | `{ids[] ≤ 50}` | `BulkJoinResult {approved, skipped: [{id, reason: POSSIBLE_MATCH}], failed: [{id, message}]}` | Each id its own transaction with `mode CREATE`; ids with a possible match are skipped and stay Pending so the manager can choose link-or-create by hand |
| `POST /api/v1/hr/join-requests/bulk/reject` | `{ids[] ≤ 50, reason?}` | `BulkJoinResult` | One reason for every selected request |

**Error codes 8035–8047 (`ErrorCode`, texts shown word for word in the app or the inbox), plus `OAUTH_ACCOUNT_NOT_FOUND` 4004 in the user range.** 8042, 8043 and 8046 are unused: they belonged to the removed legacy pairing flows (D11); the gaps are kept so nothing is renumbered.

| Code | HTTP | Message | When |
|------|------|---------|------|
| `OAUTH_ACCOUNT_NOT_FOUND` 4004 | 401 | No account found for this Google account. Please sign up first. | `POST /auth/login` with a Google ID token whose e-mail has no account; the app opens its phone step (sign-up with the same token) instead of showing the text. Not an HR code: it is an answer of `/auth/login` |
| `HR_WORKER_HAS_SHOP` 8035 | 409 | You are already part of a shop. Leave it first. | join request from an account with a shop or a linked ACTIVE employee |
| `HR_WORKER_REQUEST_PENDING` 8036 | 409 | You already have a request waiting. | second open request |
| `HR_WORKER_LOGIN_IN_OTHER_SHOP` 8037 | 409 | This login is already part of another shop. | approval that would give the account a second shop |
| `HR_REQUEST_ALREADY_HANDLED` 8038 | 409 | This request was already handled | approve, reject or withdraw on a request no longer Pending |
| `HR_MATCH_LINKED_ELSEWHERE` 8039 | 409 | %s is already linked to another login | "Link to existing" on an employee with another login (`%s` = employee code) |
| `HR_MATCH_EXITED` 8040 | 422 | %s has exited. Create a new employee instead. | "Link to existing" on an EXITED employee |
| `HR_PAIRING_WRONG_EMPLOYEE` 8041 | 403 | This code is for a different employee. Ask your manager. | Bearer claim/complete with another employee's code |
| `HR_TWO_CREDENTIALS` 8044 | 400 | A request may carry a login or a phone signature, not both. | Bearer path with an envelope header, or device path with `Authorization` |
| `HR_WORKER_NOT_ACTIVE` 8045 | 409 | You are not part of a shop. | worker pairing call from an account with no linked ACTIVE employee |
| `HR_WORKER_ONLY` 8047 | 403 | This app is for workers. Ask your manager to link your login to your employee record from the Employees page. | staff account without an employee on any worker path but `GET /me` |
| `HR_TOO_MANY_ATTEMPTS` 8028 | 429 | Too many attempts, try again later | the worker paths' text (`ErrorCode.HR_TOO_MANY_ATTEMPTS_WORKER_MESSAGE`); the punch and pairing paths keep "Too many attempts, wait a minute" |

**Jobs, indexes, metrics.** `WorkerExitJob` (every 60 s, `HR_WORKER_EXIT_*`) ends the `WORKER` membership and releases the account's shop for linked employees whose exit date has arrived (`membershipEndedAt` is the ledger; a staff account's membership is untouched) and resets join requests left `APPROVING` for over 5 minutes to `PENDING`. `HrIndexMigration` adds the `hr_join_requests` indexes (inbox list, one-open-per-account partial unique, last outcome, 90-day TTL), the `hr_employees` exit scan and possible-match indexes and `hr_notifications (recipientUserId, createdAt)`. The four `inventory_hr_worker_*` counters are incremented in one class, `HrWorkerCounters` ([docs/metrics.md](docs/metrics.md)); Grafana `03-module.json` has the panel "Join requests per event".

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
