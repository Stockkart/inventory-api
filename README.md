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
| `PUT /api/v1/inventory/{id}` | Edit one lot (product search Edit). Sent fields only; prices go to the lot's pricing record, and the response carries the prices as stored after the update |

**Removed:** `GET /inventory/near-expiry`, `GET /inventory/fefo`.

**Modes:** `regular` (full registration), `basic` (quick stock-in), `invoice` (print surfaces). Required fields respect `showIn` per mode after Phase 2 filter updates.

- **`basic` needs explicit tags.** A required field reaches `basic` only when its `showIn` is empty or lists `"basic"`; an optional field needs `tier: "basic"` or `"basic"` in `showIn`. A seed with no such fields returns an empty inventory list, and stock-in in Basic mode cannot add products. `VerticalSchemaSeedFilesTest` checks the sports seed.
- **Sports inventory fields** (`sports-v1.json`, stored in `inventory_ext_sports`): required `sport` (enum), `brand`, `model`; optional `size` (string, max 32) and `warrantyMonths`. `size` is tagged `"basic"` in `showIn`, so it appears in quick stock-in too; `warrantyMonths` stays regular-only.
- **Shop licence fields** sit in the seed's `shop` entity with `showIn: ["onboarding"]`: `dlNo` (medical), `fssai` (grocery, cafe).

**After changing seed JSON**, restart the app: `VerticalSchemaSeeder` overwrites rows it created (`createdBy` starts with `seed:`) and then `SchemaLoader.warmCache()` runs. Rows published another way are skipped, so delete or update those (e.g. `medical_1.0.0`) by hand.

**Seed mirrors:** `docs/seeds/medical-v1.json`, `docs/seeds/sports-v1.json`

**Frontend (inventory-platform):** consumes schema APIs for onboarding, product registration, scan-sell, product search (cursor pagination), and reminders expiry buckets — see architecture doc § Implementation status.

**Remaining:** M8 core field strip migration; scan-sell detail modal schema columns; apparel/cafe vertical (Phase 5); import mappers + widgets (Phase 6).

**Clearing a field on a lot edit.** In `PUT /api/v1/inventory/{id}` a field left out or sent as `null` keeps its value. To empty one, name it in `clearFields`: `barcode`, `description`, `companyName`, `location`, `hsn`, `discountApplicable`, `thresholdCount` (lot) and `saleAdditionalDiscount`, `purchaseAdditionalDiscount`, `saleScheme`, `purchaseScheme` (pricing record). Any other name, or a name also sent with a value, is a 400. A clear needs the same product-search grant as setting the field (`saleScheme` → `schemeType`, `purchaseScheme` → `purchaseSchemeType`).

### GST reports (taxation)

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/taxation/gstr1` | Outward supplies (sales) |
| `GET /api/v1/taxation/gstr2` | Inward supplies (purchases) |
| `GET /api/v1/taxation/gstr3b` | Summary return |

**Shop state is required for GSTR-1 and GSTR-2.** The shop's state comes from its GSTIN, else the state on its address. Without either, `GET /taxation/gstr1` and `GET /taxation/gstr2` (and their downloads) return **422** with error code **7000 `GST_CONFIGURATION_MISSING`**, because an interstate supply (IGST) cannot be told from a local one (CGST + SGST). GSTR-1 previously wrote an empty place of supply instead. Set the shop's GSTIN or address state, then generate the return again.

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
- **`PATCH /api/v1/pricing/{id}` and `POST /api/v1/pricing/bulk-update`:** when the request sends `rates`, `resetRates` or a `defaultRate`. Each rate sent needs a `price`, even for a terms-only override (send the current price; it is ignored for a rule rate).
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

### HR (hr)

Employees, attendance, leave, salary advances, payroll, salary payments, payslips and HR registers (`core/hr`, spec `.kiro/specs/hr-module`). Every path needs a Bearer token; there is no public HR path. Staff paths sit behind the RBAC module `hr` (member switch `modules.hr`, `null` = on) and an action permission from the shop's HR role grid (owner: all); `/api/v1/hr/me/**` skips the module check and works only on the caller's own linked employee (`404 HR_NOT_LINKED` when the login has none, except notifications). Shop and caller always come from the request attributes. Pay amounts and full bank/UPI details need `VIEW_SALARIES`. Endpoints marked *heavy* run under the heavy-request limiter kind `hr` (2 at once, 4 waiting, 10 s, then 503 with `Retry-After`). Plan expiry blocks HR like the rest of the app, except `/api/v1/hr/me/payslips/**`.

| Endpoint | Permission | Purpose |
|----------|------------|---------|
| `GET /api/v1/hr/settings`, `PUT /api/v1/hr/settings`, `GET /api/v1/hr/setup-status` | any HR permission; PUT `MANAGE_SETTINGS` | Weekly off (with `effectiveFrom`), hours, grace, half-day, overtime, per-day basis, leave-year start |
| `GET`, `PUT /api/v1/hr/role-permissions` | owner | Role grid, closed under the dependency rules on save |
| `GET`, `POST /api/v1/hr/holidays`, `PUT`, `DELETE /api/v1/hr/holidays/{id}` | GET any HR; writes `MANAGE_SETTINGS` | Holidays per year |
| `GET`, `POST /api/v1/hr/leave-types`, `PUT /api/v1/hr/leave-types/{id}` | GET any HR; writes `MANAGE_SETTINGS` | Leave types (three seeded defaults) |
| `GET`, `POST /api/v1/hr/employees`, `GET`, `PUT /api/v1/hr/employees/{id}`, `POST /api/v1/hr/employees/{id}/salary-revisions`, `POST /api/v1/hr/employees/{id}/exit`, `GET /api/v1/hr/employees/designations`, `GET /api/v1/hr/linkable-logins` | `VIEW_EMPLOYEES` / `ADD_EDIT_EMPLOYEES` (+ `VIEW_SALARIES` for pay) | Employees, masked without `VIEW_SALARIES` |
| `GET /api/v1/hr/attendance/daily?asOf=`, `PUT /api/v1/hr/attendance/days` (≤ 500 rows), `POST /api/v1/hr/attendance/mark-unmarked-present`, `GET /api/v1/hr/attendance/month?period=&employeeId=`, `GET /api/v1/hr/attendance/history?employeeId=&date=` | `VIEW_ATTENDANCE` / `MARK_ATTENDANCE` | Daily and month registers, bulk save, history with actor names |
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
