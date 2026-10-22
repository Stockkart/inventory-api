# Stockkart Backend (inventory-api) — Agent Context

## Purpose

Spring Boot multi-module backend for Stockkart, on MongoDB.
This file is the contract for layering, naming, errors, persistence, config and tests in this repository.
Every rule has an ID (cite it in review) and one `Ref:` to a file that follows it.
Counts and lists are as of the commit in the Commands section; each long list carries the command that regenerates it.
Cross-repo topics live in the handbook: handbook ARCHITECTURE.md (planned), handbook API_CONTRACT.md (planned), handbook REVIEW_RUBRIC.md (planned), handbook RUNBOOK.md (planned), handbook decisions/ (planned).

## Tech stack and versions

- S1. Target Java 21. Ref: `pom.xml`
- S2. Spring Boot 3.3.0 is imported as a BOM, not used as parent; plugins therefore get no managed version. `app/pom.xml` and `core/pom.xml` declare `spring-boot-maven-plugin` and `maven-compiler-plugin` without a version, so Maven warns and resolves the newest release. Always give a new plugin an explicit version. Ref: `pom.xml`
- S3. Persist with Spring Data MongoDB only. No SQL or JPA. Ref: `core/pom.xml`
- S4. Use Lombok (BOM version) and MapStruct 1.5.5.Final for boilerplate and mapping. Ref: `core/pom.xml`
- S5. Use jqwik 1.9.2 for property tests; it is declared in product only. Ref: `core/product/pom.xml`
- S6. Use Apache POI 5.2.5, openhtmltopdf 1.0.10 and Thymeleaf for documents, in documentservice only. Ref: `core/documentservice/pom.xml`
- S7. Build with Maven 3.9+. There is no `mvnw` script; only the wrapper properties file exists. Ref: `app/.mvn/wrapper/maven-wrapper.properties`
- S8. Test with JUnit 5, Mockito and AssertJ from `spring-boot-starter-test`. Surefire preloads the byte-buddy agent through `argLine`; keep it. Ref: `pom.xml`

## Repository layout

| Path | Holds |
|---|---|
| `app/` | Boot entry (`AppApplication`), interceptors, Mongo/CORS/security config, observability |
| `core/<module>/` | 15 business modules (accounting, analytics, common, credit, documentservice, metrics, notifications, ocr, plan, pricing, product, reminders, resource, taxation, user) |
| `pluginengine/` | Vertical plugin SPI and `PluginRegistry` |
| `plugins/<vertical>/` | medical, grocery, sports, cafe |
| `docs/` | `deploying.md`, `metrics.md` |
| `scripts/` | deploy scripts and two manual Mongo scripts (lot-id migration; `super-user.mongodb.js`, the only way to grant or revoke SUPER_USER, documented in `docs/deploying.md`) |
| `grafana/`, `.github/workflows/` | dashboards; CI |

- R1. Lay out a module as `com.inventory.<module>.{domain.model, domain.repository, service, rest.controller, rest.dto.request, rest.dto.response, mapper, validation, utils, utils.constants, migration}`. Ref: `core/user/src/main/java/com/inventory/user/rest/controller/VendorController.java`
  - Exceptions: enums in `domain/model/enums`, not `domain/enums` as README says (`core/product/src/main/java/com/inventory/product/domain/model/enums`).
  - Flat `rest/dto`: `core/taxation/src/main/java/com/inventory/taxation/rest/dto`, `core/analytics/src/main/java/com/inventory/analytics/mis/rest/dto`, `core/documentservice/src/main/java/com/inventory/documentservice/rest/dto`.
  - `dto` outside `rest`: `core/ocr/src/main/java/com/inventory/ocr/dto`, `core/plan/src/main/java/com/inventory/plan/payment/dto`.
  - Feature package with its own controller: `core/taxation/src/main/java/com/inventory/taxation/gstin/GstinController.java`.
  - `util` next to `utils`: `core/product/src/main/java/com/inventory/product/util`, `core/ocr/src/main/java/com/inventory/ocr/util`. `core/common` uses `util` only.
- R2. Lay out a plugin as: root `<Vertical>Plugin extends ConfiguredVerticalPlugin`, `<Vertical>VerticalProperties` (`vertical.plugins.<id>`), `<Vertical>InventorySearchProvider`; `domain/` with `<Vertical>InventoryExtension` and `<Vertical>ExtensionMongoRepository`; `repository/` with `<Vertical>InventoryExtensionRepository`. Ref: `plugins/sports/src/main/java/com/inventory/plugins/sports/SportsPlugin.java`
  - Exceptions: grocery nests the extension and properties under `domain/model` and both repositories under `domain/repository` (`plugins/grocery/src/main/java/com/inventory/plugins/grocery/domain/model/GroceryVerticalProperties.java`).
  - Only medical and grocery have `search/<Vertical>SearchSchema` (`plugins/medical/src/main/java/com/inventory/plugins/medical/search/MedicalSearchSchema.java`).
  - cafe adds root-level contributors and handlers (`plugins/cafe/src/main/java/com/inventory/plugins/cafe/CafePricingPolicy.java`).
- R3. Define vertical fields in seed JSON under `core/product/src/main/resources/seeds/`; `VerticalSchemaSeeder` loads them at boot. Ref: `core/product/src/main/resources/seeds/sports-v1.json`

README drift (facts, not rules):
- `docs/VERTICAL_PLUGIN_ARCHITECTURE.md` and `docs/seeds/*.json` named in README.md do not exist.
- README says `.env` loads from the project root. That holds for `java -jar` run from the root; `mvn -pl app spring-boot:run` reads `app/.env`.
- README cites "error code 7000"; the numeric code is not in the response body (E2).

## Layering and module boundaries

Module dependencies (from each `pom.xml`):

| Module | May depend on |
|---|---|
| `common` | — |
| `metrics` | common |
| `notifications`, `ocr`, `pricing`, `reminders`, `resource` | common, metrics |
| `user` | common, notifications, metrics |
| `accounting`, `credit`, `plan` | common, user, metrics |
| `documentservice` | metrics |
| `pluginengine` | common |
| `plugins/*` | common, pluginengine (cafe also metrics) |
| `product` | common, metrics, user, credit, accounting, pricing, pluginengine, reminders, documentservice, ocr, plan |
| `taxation` | common, metrics, product, user, pricing |
| `analytics` | common, metrics, product, credit, documentservice |
| `app` | everything |

- L1. Call another module only along an existing pom edge. Add a new edge only if it creates no cycle. Nothing but `app` depends on `app` or `plugins/*`. Ref: `core/taxation/pom.xml`
- L2. To call upward, declare an interface in the lower module and implement it in the higher one as `<Name>Impl`; inject optional implementations with `@Autowired(required = false)`. Ref: `core/user/src/main/java/com/inventory/user/service/ShopServiceAdapter.java`
  - Implemented by `core/product/src/main/java/com/inventory/product/service/ShopServiceAdapterImpl.java`. Other ports (names vary): `core/plan/src/main/java/com/inventory/plan/service/ShopProvider.java`, `core/reminders/src/main/java/com/inventory/reminders/service/InventoryAdapter.java`, `core/pricing/src/main/java/com/inventory/pricing/service/VerticalPricingPolicyPort.java`, `core/common/src/main/java/com/inventory/common/gst/SupplyPlacement.java`, `core/common/src/main/java/com/inventory/common/gst/GstinDirectory.java`.
- L3. Controllers call services of their own module only. Never inject a repository or `MongoTemplate` into a controller (0 of 54 `@RestController` classes do). Ref: `core/user/src/main/java/com/inventory/user/rest/controller/CustomerController.java`
- L4. Put business logic, validator calls and mapping in the service, not the controller. Ref: `core/user/src/main/java/com/inventory/user/service/VendorService.java`
  - Validator in controller. Dominant: injected in 33 service files. Exceptions (5): `core/reminders/src/main/java/com/inventory/reminders/rest/controller/EventController.java`, `core/reminders/src/main/java/com/inventory/reminders/rest/controller/ReminderController.java`, `core/plan/src/main/java/com/inventory/plan/rest/controller/PaymentWebhookController.java`, `core/pricing/src/main/java/com/inventory/pricing/rest/controller/PricingController.java`, `core/user/src/main/java/com/inventory/user/rest/controller/UserShopController.java`. List: `grep -rlE '(private|final) +[A-Z]\w*Validator +\w+ *;' --include=*.java core/*/src/main/java | grep /rest/controller/`.
  - Mapper in controller. Exceptions (5): `core/plan/src/main/java/com/inventory/plan/rest/controller/PaymentWebhookController.java`, `core/user/src/main/java/com/inventory/user/rest/controller/AuthController.java`, `core/user/src/main/java/com/inventory/user/rest/controller/VendorController.java`, `core/accounting/src/main/java/com/inventory/accounting/rest/controller/AccountingController.java`, `core/credit/src/main/java/com/inventory/credit/rest/controller/CreditController.java`. List: `grep -lE 'Mapper' $(grep -rlw '@RestController' --include='*Controller.java' core app plugins)`.
- L5. Use another module through its service or facade. Ref: `core/accounting/src/main/java/com/inventory/accounting/api/AccountingFacade.java`
  - Exceptions: 24 files read another module's repository directly (product 12, analytics 7, taxation 4, credit 1), e.g. `core/taxation/src/main/java/com/inventory/taxation/service/Gstr1DataAggregator.java`. List: `for m in $(ls core); do grep -rlE '^import com\.inventory\.[a-z]+\.domain\.repository\.' core/$m/src/main/java 2>/dev/null | while read f; do grep -oE '^import com\.inventory\.[a-z]+\.domain\.repository\.' "$f" | grep -vq "inventory\.$m\." && echo "$f"; done; done`.
- L6. Take the tenant shop only from request attribute `shopId`, which `AuthenticationInterceptor` sets (`X-Shop-Id` header if the user is a member, else the active shop). Never scope by a shop id from the body. Shop-owned documents carry `shopId` (40 of 49 `@Document` classes); global ones (Vendor, Customer, Plan, …) link through `shop_vendors`/`shop_customers`. Ref: `app/src/main/java/com/inventory/app/interceptor/AuthenticationInterceptor.java`
- L7. The request pipeline is `AuthenticationInterceptor` → `RbacModuleInterceptor` (path-prefix module rules) → `PlanExpiryInterceptor` (402 when the plan expired, allow-list), all registered in `CorsConfig`. Spring Security supplies only `BCryptPasswordEncoder` (`app/src/main/java/com/inventory/app/config/SecurityConfig.java`); do not add a security filter chain. Ref: `app/src/main/java/com/inventory/app/config/CorsConfig.java`
- L8. Plugins reach core only through `pluginengine` SPI types; core reaches plugins only through `PluginRegistry`. Ref: `pluginengine/src/main/java/com/inventory/pluginengine/PluginRegistry.java`

## Naming conventions

- N1. Name packages `com.inventory.<module>`; plugins `com.inventory.plugins.<vertical>`. Ref: `plugins/sports/src/main/java/com/inventory/plugins/sports/SportsPlugin.java`
- N2. Name classes `<Thing>Controller`, `<Thing>Service` (a concrete `@Service`; no interface, 0 `*ServiceImpl` exist), `<Thing>Repository`, `<Thing>Mapper`, `<Thing>Validator`, `<Thing>Utils`, `<Module>MetricsConstants`. Ref: `core/user/src/main/java/com/inventory/user/service/VendorService.java`
- N3. Name request DTOs `<Verb><Thing>Request` in `rest/dto/request`, response DTOs `<Thing>Response` (dominant) or `<Thing>Dto` in `rest/dto/response`. Write DTOs as Lombok `@Data` classes; records appear in product only. Ref: `core/user/src/main/java/com/inventory/user/rest/dto/request/CreateVendorRequest.java`
  - Exceptions (6): `core/plan/src/main/java/com/inventory/plan/rest/dto/request/PaymentWebhookPayload.java`, `core/product/src/main/java/com/inventory/product/rest/dto/request/LenientBoolean.java`, `core/product/src/main/java/com/inventory/product/rest/dto/request/PrintBridgeObservation.java`, `core/pricing/src/main/java/com/inventory/pricing/rest/dto/request/PricingCreateCommand.java`, `core/pricing/src/main/java/com/inventory/pricing/rest/dto/request/PricingUpdateCommand.java`, `core/pricing/src/main/java/com/inventory/pricing/rest/dto/request/UpdateDefaultPriceItem.java`. List: `find . -path '*/rest/dto/request/*' -name '*.java' | grep -vE 'Request\.java$'`.
- N4. Map controllers at class level with `@RequestMapping("/api/v1/<kebab-case>")`. Use plural nouns for collections (`/customers`, `/vendor-purchase-invoices`) and mass nouns for module roots (`/accounting`, `/analytics`, `/mis`, `/taxation`, `/inventory`, `/pricing`, `/credit`, `/auth`, `/dashboard`). Put shop settings under `/api/v1/shops/active-shop/<setting>`. JSON is camelCase (Jackson default, no `spring.jackson.*` config). Ref: `core/user/src/main/java/com/inventory/user/rest/controller/CustomerController.java`
  - No class-level path (none, or bare `@RequestMapping`): `core/product/src/main/java/com/inventory/product/rest/controller/QRUploadController.java`, `core/user/src/main/java/com/inventory/user/rest/controller/ShopUserController.java`.
  - Bare `/api/v1` with full method paths: `core/product/src/main/java/com/inventory/product/rest/controller/CheckoutController.java`, `core/user/src/main/java/com/inventory/user/rest/controller/InvitationController.java`, `core/user/src/main/java/com/inventory/user/rest/controller/JoinRequestController.java`.
  - Singular count noun `/api/v1/refund`: `core/product/src/main/java/com/inventory/product/rest/controller/RefundController.java`.
  - Only `/m/**` (public mobile upload in `QRUploadController`) sits outside `/api`. `/admin/**` is an interceptor/CORS pattern with no controller; admin actions are sub-paths such as `/api/v1/accounting/admin/backfill`.
  - List: `grep -rhoE '@RequestMapping\("[^"]*"\)' --include='*Controller.java' core | sort | uniq -c`.
- N5. Use GET to read (131 mappings) and POST to create or run actions (64). Updates use PUT (10) or PATCH (9); match the verb the resource already uses. Ref: `core/user/src/main/java/com/inventory/user/rest/controller/VendorController.java`
- N6. Name collections in plural `snake_case` with `@Document(collection = "...")`. Ref: `core/user/src/main/java/com/inventory/user/domain/model/Vendor.java`
  - Non-plural (6): `core/product/src/main/java/com/inventory/product/domain/model/Inventory.java`, `core/product/src/main/java/com/inventory/product/domain/model/Product.java`, `core/pricing/src/main/java/com/inventory/pricing/domain/model/Pricing.java`, `core/plan/src/main/java/com/inventory/plan/domain/model/Usage.java`, `core/product/src/main/java/com/inventory/product/domain/model/BarcodePool.java`, `core/taxation/src/main/java/com/inventory/taxation/gstin/GstinRecord.java`.
  - Plugin extensions use `inventory_ext_<vertical>` in all four plugins (`plugins/sports/src/main/java/com/inventory/plugins/sports/domain/SportsInventoryExtension.java`).
  - Shorthand `@Document("...")` (3): `core/reminders/src/main/java/com/inventory/reminders/domain/model/Event.java`, `core/reminders/src/main/java/com/inventory/reminders/domain/model/Reminder.java`, `core/notifications/src/main/java/com/inventory/notifications/domain/model/OutboundMessage.java`.
  - List: `grep -rnoE '@Document\((collection *= *)?"[a-z_]+"' --include=*.java core plugins`.
- N7. Name metrics `inventory_<module>_<thing>` as constants in `<module>/utils/constants/<Module>MetricsConstants.java`. End counters in `_total`; gauges, amounts and timers do not (7 of 50 constants). Ref: `core/user/src/main/java/com/inventory/user/utils/constants/UserMetricsConstants.java`
  - At the plugin root: `plugins/cafe/src/main/java/com/inventory/plugins/cafe/CafeMetricsConstants.java`.
  - Short module name in the class: `core/documentservice/src/main/java/com/inventory/documentservice/utils/constants/DocumentMetricsConstants.java`, `core/reminders/src/main/java/com/inventory/reminders/utils/constants/ReminderMetricsConstants.java`, `core/notifications/src/main/java/com/inventory/notifications/utils/constants/NotificationMetricsConstants.java`.
  - Non-`_total` constants live in `core/taxation/src/main/java/com/inventory/taxation/utils/constants/TaxationMetricsConstants.java`, `core/plan/src/main/java/com/inventory/plan/utils/constants/PlanMetricsConstants.java`, `core/product/src/main/java/com/inventory/product/utils/constants/ProductMetricsConstants.java`, `core/ocr/src/main/java/com/inventory/ocr/utils/constants/OcrMetricsConstants.java`. List: `grep -rhoE '"inventory_[a-z_]+"' --include='*MetricsConstants.java' . | grep -v '_total"'`.
- N8. Name property keys in kebab-case under a feature prefix (`stockkart.<job>.enabled`, `print-bridge.*`, `vertical.plugins.<id>.*`); name env vars UPPER_SNAKE. Ref: `app/src/main/resources/application.properties`
- N9. Indent Java with 2 spaces. Log with Lombok `@Slf4j`; `System.out` appears only in `AppApplication`. No formatter or linter is configured. Ref: `core/user/src/main/java/com/inventory/user/service/VendorService.java`
  - 4-space outliers: `core/common/src/main/java/com/inventory/common/gst/SupplyPlacement.java`, `core/user/src/main/java/com/inventory/user/service/ShopServiceAdapter.java`. Poms use 4 spaces.
- N10. Inject with field `@Autowired` (dominant: 43 of 54 controllers); constructor injection also exists. Match the file you edit. Ref: `core/user/src/main/java/com/inventory/user/rest/controller/CustomerController.java`
  - Controller exceptions (11), e.g. `core/accounting/src/main/java/com/inventory/accounting/rest/controller/AccountingController.java`, `core/credit/src/main/java/com/inventory/credit/rest/controller/CreditController.java`, `core/taxation/src/main/java/com/inventory/taxation/gstin/GstinController.java`. List: `grep -L '@Autowired' $(grep -rlw '@RestController' --include=*.java core app plugins)`.

## Error handling and API error shape

- E1. Return `ResponseEntity<ApiResponse<T>>` built with `ApiResponse.success(data)` from every JSON endpoint. Ref: `core/common/src/main/java/com/inventory/common/dto/response/ApiResponse.java`
  - Non-JSON by design: file downloads return `ResponseEntity<byte[]>` (GSTR, MIS, PDF); dot-matrix and preview text return `ResponseEntity<String>`; SSE returns `SseEmitter` (`core/reminders/src/main/java/com/inventory/reminders/rest/controller/EventController.java`).
- E2. Expect this error envelope; `errors` appears only for validation failures, the numeric `ErrorCode.code` is not serialized, and `core/common/src/main/java/com/inventory/common/dto/response/ErrorResponse.java` is unused. Ref: `core/common/src/main/java/com/inventory/common/advice/GlobalExceptionHandler.java`

```json
{"success":false,"error":"<message>","data":{"message":"<message>","status":<http>,"errors":{"<field|general>":["..."]}}}
```

- E3. Map exceptions to statuses only through `ErrorCode` and `GlobalExceptionHandler`, as in this table. Ref: `core/common/src/main/java/com/inventory/common/constants/ErrorCode.java`

| Exception | Status | Notes |
|---|---|---|
| `BaseException(ErrorCode.X, msg)` and subclasses not below | X's status | `ResourceNotFoundException` 404, `ResourceExistsException` 409, `InsufficientStockException` 400, `GstConfigurationException` 422 |
| `ValidationException` | 400 | `errors.general` |
| `AuthenticationException` | 401 always | its `ErrorCode` is ignored for the status |
| Spring Security `BadCredentialsException` | 401 | "Invalid username or password" |
| Spring Security `AccessDeniedException` | 403 | |
| `ConstraintViolationException`, `@Valid` failure | 400 | per-field `errors` |
| Malformed JSON | 400 | "Malformed JSON request" |
| `AsyncRequestNotUsableException`, `IOException` | — | swallowed and logged (`ignoreSseDisconnects`); never reaches the client |
| Any other `Exception` | 500 | "An unexpected error occurred" |

- E4. Throw the `core/common` exceptions from services and validators; `throw new ValidationException(...)` dominates (553 throws in 75 main-source files). Ref: `core/user/src/main/java/com/inventory/user/validation/VendorValidator.java`
  - Exceptions: 15 service files throw `IllegalArgumentException`/`IllegalStateException`/`RuntimeException`, which become 500, e.g. `core/taxation/src/main/java/com/inventory/taxation/service/Gstr1DataAggregator.java`, `core/accounting/src/main/java/com/inventory/accounting/service/JournalEntrySequenceService.java`, `core/ocr/src/main/java/com/inventory/ocr/service/OcrService.java`. List: `grep -rlE 'throw new (IllegalArgument|IllegalState|Runtime)Exception' core/*/src/main/java | grep -E '/service/|/rest/'`.
- E5. Add new error codes to `ErrorCode` in the domain range (1xxx common, 2xxx business, 3xxx product, 4xxx user, 5xxx order, 6xxx plan, 7xxx tax) with their `HttpStatus`; subclass `BaseException` when a dedicated type helps. Ref: `core/common/src/main/java/com/inventory/common/exception/GstConfigurationException.java`
- E6. Validate imperatively in a `@Component <Thing>Validator`. Ref: `core/user/src/main/java/com/inventory/user/validation/VendorValidator.java`
  - Bean Validation (`@Valid`) appears in 2 controllers only: `core/accounting/src/main/java/com/inventory/accounting/rest/controller/AccountingController.java`, `core/credit/src/main/java/com/inventory/credit/rest/controller/CreditController.java`.
- E7. In services, check that `shopId` is present and throw `AuthenticationException(ErrorCode.UNAUTHORIZED, …)` when it is missing. Ref: `core/user/src/main/java/com/inventory/user/service/VendorService.java`
- E8. Do not rely on the envelope for Spring MVC framework errors (missing `@RequestParam` 400, unknown path 404, wrong method 405); they return RFC 7807 ProblemDetail `{type,title,status,detail,instance}`, because only `handleMethodArgumentNotValid` and `handleHttpMessageNotReadable` are overridden. Ref: `core/common/src/main/java/com/inventory/common/advice/GlobalExceptionHandler.java`

## Persistence and migrations

MongoDB: "table" means collection, "column" means field.

- P1. Add a collection as a Lombok `@Data` class with `@Document(collection = "<snake_plural>")` in `<module>/domain/model`, with `@Id String id`, `String shopId` if shop-owned, and `Instant createdAt`/`updatedAt` set in code (no Mongo auditing is configured). Ref: `core/user/src/main/java/com/inventory/user/domain/model/Vendor.java`
- P2. Add a repository as `interface <Thing>Repository extends MongoRepository<Thing, String>` in `<module>/domain/repository`, with derived `findByShopIdAnd…` queries or `@Query`. Ref: `core/user/src/main/java/com/inventory/user/domain/repository/ShopVendorRepository.java`
  - Custom queries use `MongoTemplate` either in a `<Thing>RepositoryCustom` + `<Thing>RepositoryImpl` pair (`core/pricing/src/main/java/com/inventory/pricing/domain/repository/PricingRepositoryImpl.java`) or in services (`core/accounting/src/main/java/com/inventory/accounting/service/TrialBalanceService.java`). Match the module you edit. List: `grep -rl MongoTemplate --include=*.java core | grep /service/`.
- P3. Add any new repository package to `@EnableMongoRepositories(basePackages = …)`, or it is not scanned. Ref: `app/src/main/java/com/inventory/app/AppApplication.java`
- P4. Create indexes in an idempotent `ensureIndex` runner. Automatic index creation is off (no `auto-index-creation` property), so `@Indexed`/`@CompoundIndex` on core models do nothing by themselves; plugin extension annotations are created by this runner. Ref: `core/product/src/main/java/com/inventory/product/migration/SearchIndexMigration.java`
- P5. Add a field by adding it to the model. Existing documents read it as `null`; treat `null` as the old behaviour and Javadoc that default. Ref: `core/user/src/main/java/com/inventory/user/domain/model/Vendor.java`
- P6. Backfill data with a `@Component` in `<module>/migration/`: an `@EventListener(ApplicationReadyEvent.class)` method with an unused `@Order(n)`, an idempotent query that touches only rows still needing the change, `enabled` (and optionally `dry-run`) via `@Value("${stockkart.<job>.enabled:…}")`, a passthrough `stockkart.<job>.enabled=${<JOB>_ENABLED:…}` in `app/src/main/resources/application.properties` (it sets the effective default), and the affected count logged before and after. Ref: `core/product/src/main/java/com/inventory/product/migration/SellRestrictionBackfillRunner.java`
  - Orders in use: 5 (`ProductBarcodeIndexMigration`, `GridLayoutIndexMigration`), 6 (`SearchIndexMigration`), 7 (`LedgerIndexMigration`), 8 (`UserTokenIndexMigration`), 9 (`CreditAccountIndexMigration`), 10 (`VerticalSchemaSeeder`), 11 (`ShopPartyIndexMigration`).
  - No `enabled` flag: `core/product/src/main/java/com/inventory/product/migration/VerticalSchemaSeeder.java`, `core/product/src/main/java/com/inventory/product/migration/ProductBarcodeIndexMigration.java`.
  - `enabled` but no passthrough: `core/product/src/main/java/com/inventory/product/migration/InvoiceSequenceBackfillRunner.java`, `core/user/src/main/java/com/inventory/user/migration/VendorPlaceMigration.java`, `core/product/src/main/java/com/inventory/product/migration/SearchIndexMigration.java`.
  - `dry-run` exists only in the 4 runners that also have the passthrough: SellRestriction, TxnId, Product and LotBaseCountRepair backfills.
  - Error handling, no single convention. Propagate (4): `core/product/src/main/java/com/inventory/product/migration/SellRestrictionBackfillRunner.java`, `core/product/src/main/java/com/inventory/product/migration/TxnIdBackfillRunner.java`, `core/product/src/main/java/com/inventory/product/migration/ProductBackfillRunner.java` (catches only inside value parsers), `core/product/src/main/java/com/inventory/product/migration/LotBaseCountRepairRunner.java`. Catch and log (5): `core/user/src/main/java/com/inventory/user/migration/VendorPlaceMigration.java`, `core/product/src/main/java/com/inventory/product/migration/InvoiceSequenceBackfillRunner.java`, `core/product/src/main/java/com/inventory/product/migration/SearchIndexMigration.java`, `core/product/src/main/java/com/inventory/product/migration/ProductBarcodeIndexMigration.java`, `core/product/src/main/java/com/inventory/product/migration/VerticalSchemaSeeder.java`. Choosing one is for a handbook decisions/ ADR (planned).
  - Runner list (split by reading each listener method): `grep -rl ApplicationReadyEvent --include=*.java core/*/src/main/java/com/inventory/*/migration`.
  - Not a runner: the one-off manual script `scripts/migrate-lot-id-to-stock-in.mongodb.js`, and the operator script `scripts/super-user.mongodb.js`.
- P7. Store money as `BigDecimal`; no domain model has a `double`/`float` money field (the only `Double` is `rollColumnGapMm` in `core/product/src/main/java/com/inventory/product/domain/model/LabelLayoutDocument.java`). Converters store it as Decimal128. Ref: `app/src/main/java/com/inventory/app/config/MongoConfig.java`
- P8. Do not rely on `@Transactional` for multi-document atomicity: it appears in 59 files, but no `MongoTransactionManager` or other `TransactionManager` bean is declared. Ref: `core/user/src/main/java/com/inventory/user/service/VendorService.java`
- P9. Change vertical schemas by editing `core/product/src/main/resources/seeds/<vertical>-v1.json`. On restart `VerticalSchemaSeeder` overwrites only rows whose `createdBy` starts with `seed:`. Run `VerticalSchemaSeedFilesTest` after. Ref: `core/product/src/main/java/com/inventory/product/migration/VerticalSchemaSeeder.java`

## Configuration and environment variables

- C1. Read config in Java only through property keys: `@Value("${key:default}")` or `@ConfigurationProperties`. Map env vars only in `application.properties` as `key=${ENV_NAME}` (optional default). Never call `System.getenv` (0 uses in main code). Ref: `core/product/src/main/java/com/inventory/product/config/PrintBridgeProperties.java`
- C2. Add a variable in three places: the property in `app/src/main/resources/application.properties`, the name in `.env.example`, and a passthrough under `services.app.environment` in `docker-compose.yml`. Ref: `docker-compose.yml`
  - `.env.example` values: dominant empty (30 of 42). Exceptions: `SPRING_PROFILES_ACTIVE`, `PAYMENT_GATEWAY`, `GSTIN_PROVIDER`, the five `AUTH_*`, the three `SHOP_CACHE_*` settings and `MONGO_POOL_MAX_WAIT_MS` carry a non-secret default (an empty TTL or max-entries value fails boot, C3).
  - In `.env.example` only: `DB_USERNAME`, `DB_PASSWORD` (for the commented-out local `mongodb` service), `GSTIN_PROVIDER`, `GSTINAPI_KEY`. In compose only: `MESSAGING_DISPATCH_INTERVAL_MS`.
- C3. Give every placeholder without a default a value, or boot fails with "Could not resolve placeholder" (e.g. `OCR_PREPROCESS_MODE`). `UPLOAD_TOKEN_EXPIRY_MINUTES` has no default and must be an integer, so the empty value from `.env.example` fails boot. The API key for the provider chosen by `OCR_PROVIDER` must be non-empty, or boot fails; `core/ocr/src/main/java/com/inventory/ocr/config/OcrConfig.java` maps providers to keys. Ref: `app/src/main/resources/application.properties`
- C4. Never commit `.env`; CI hygiene fails on tracked `.env` files. `AppApplication` loads `.env` from the process working directory. Ref: `.github/workflows/ci-hygiene.yml`

| Name | Purpose | Declared in |
|---|---|---|
| `DB_URI` | MongoDB connection URI (secret, never commit) | both |
| `DB_USERNAME`, `DB_PASSWORD` | root user for the commented-out local Mongo container; not read by the app (secret) | .env.example |
| `CLIENT_URL` | frontend origin for CORS and links in emails | both |
| `SPRING_PROFILES_ACTIVE` | active Spring profile | both |
| `OCR_PROVIDER` | which OCR provider parses invoice images | both |
| `OCR_OPENAI_MODEL`, `OCR_GEMINI_MODEL` | model name per OCR provider | both |
| `OCR_PREPROCESS_MODE` | image preprocessing mode (required) | both |
| `OCR_PREPROCESS_URL` | preprocessing service URL (http mode) | both |
| `OCR_PREPROCESS_PYTHON_PATH`, `OCR_PREPROCESS_WORKING_DIR` | local Python preprocessing interpreter and directory | both |
| `OPENAI_API_KEY`, `GEMINI_API_KEY` | OCR provider API keys (secret, never commit) | both |
| `AWS_ACCESS_KEY`, `AWS_SECRET_ACCESS` | AWS credentials for the Textract OCR provider (secret, never commit) | both |
| `AWS_REGION` | AWS region for Textract | both |
| `UPLOAD_TOKEN_EXPIRY_MINUTES` | lifetime of mobile upload tokens | both |
| `MESSAGING_DISPATCH_INTERVAL_MS` | outbound message dispatch interval | compose |
| `MESSAGING_EMAIL_ENABLED` | turn email sending on or off | both |
| `RESEND_API_KEY` | Resend email API key (secret, never commit) | both |
| `RESEND_FROM_EMAIL`, `RESEND_FROM_NAME` | sender address and name | both |
| `AUTH_CACHE_ENABLED`, `AUTH_CACHE_TTL_SECONDS`, `AUTH_CACHE_MAX_ENTRIES` | per-instance login check cache (`AuthCache`): on/off, seconds an entry lives, entries per cache | both |
| `AUTH_TOKEN_CLEANUP_ENABLED`, `AUTH_TOKEN_CLEANUP_CRON` | nightly removal of expired login tokens (`ExpiredTokenCleanupJob`): on/off, Spring cron in Asia/Kolkata | both |
| `SHOP_CACHE_ENABLED`, `SHOP_CACHE_TTL_SECONDS`, `SHOP_CACHE_MAX_ENTRIES` | per-instance shop read cache (`ShopReadCache`): on/off, seconds an entry lives, entries | both |
| `MONGO_POOL_MAX_WAIT_MS` | longest wait for a free MongoDB connection before a 503 "busy" with `Retry-After` (`MongoPoolConfig`) | both |
| `GRAFANA_CLOUD_OTLP_URL`, `GRAFANA_CLOUD_OTLP_USER` | OTLP metrics export endpoint and user (optional) | both |
| `GRAFANA_CLOUD_LOKI_URL`, `GRAFANA_CLOUD_LOKI_USER` | Loki log push endpoint and user (optional) | both |
| `GRAFANA_CLOUD_API_TOKEN` | Grafana Cloud token (secret, never commit) | both |
| `PAYMENT_GATEWAY` | which payment gateway plans use | both |
| `RAZORPAY_KEY_ID` | Razorpay key id | both |
| `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` | Razorpay API and webhook secrets (secret, never commit) | both |
| `GSTIN_PROVIDER` | online GSTIN lookup provider; unset means offline check only | .env.example |
| `GSTINAPI_KEY` | GSTIN lookup API key (secret, never commit) | .env.example |

Optional overrides read by `application.properties` with a built-in default, in neither file (names only): `ASYNC_*`, `OCR_EXECUTOR_*`, `SSE_EMITTER_TIMEOUT_MS`, `PLAN_TRIAL_DAYS`, `PRINT_BRIDGE_*`, `GSTINAPI_BASE_URL`, `VERTICAL_<ID>_ID`/`VERTICAL_<ID>_VERSION`, `*_BACKFILL_ENABLED`/`*_BACKFILL_DRY_RUN`, `LOT_BASE_COUNT_REPAIR_*`.

## Testing

| Code | Test level |
|---|---|
| Pure logic, utils | plain JUnit 5 |
| Validators | construct directly, `assertThrows(ValidationException.class, …)` |
| Services | Mockito unit test |
| Mongo queries and indexes | gated `*IT` against a real MongoDB |
| Seed JSON | seed-file test |
| Controllers | no controller, MockMvc, `@SpringBootTest` or `@DataMongoTest` tests exist; check by running the app (A10) |

- T1. Test pure logic with plain JUnit 5 and no Spring context. Ref: `core/common/src/test/java/com/inventory/common/gst/GstinTest.java`
- T2. Test services with `@ExtendWith(MockitoExtension.class)`, `@Mock` collaborators and `@InjectMocks`. Ref: `core/user/src/test/java/com/inventory/user/service/VendorServiceUpdateTaxTreatmentTest.java`
- T3. Test validators by constructing them. Ref: `core/user/src/test/java/com/inventory/user/validation/VendorValidatorTest.java`
- T4. Name tests `<ClassUnderTest>[Aspect]Test`, package-private, in the class's package (101 of 102 test classes are package-private). Name methods as camelCase sentences (`anExplicitChoiceReplacesTheDefault`; 29 test methods use underscores). Assert with JUnit `Assertions` (88 files; AssertJ in 14). Ref: `core/user/src/test/java/com/inventory/user/service/VendorServiceUpdateTaxTreatmentTest.java`
- T5. Write property tests with jqwik `@Property`, in product only. Ref: `core/product/src/test/java/com/inventory/product/labels/LabelLayoutValidatorProperties.java`
  - The 19 `*Properties` classes do not match surefire's default includes, so `mvn test` skips them; run Commands #3.
  - `@Property` methods in a `*Test` class do run under `mvn test`. The only one, `core/product/src/test/java/com/inventory/product/labels/LabelModelSmokeTest.java`, is reported by both the Jupiter and jqwik engines, so reported totals vary between runs.
- T6. Name Mongo integration tests `*IT` and gate them with `@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")`; `mvn test` does not run them. Ref: `core/product/src/test/java/com/inventory/product/search/InventorySearchEngineIT.java`
- T7. Build fixtures in code: a `<Feature>TestFixtures` static-factory class and `InMemory<Thing>Repository` fakes in the test package. There is no `src/test/resources` anywhere. Ref: `core/product/src/test/java/com/inventory/product/search/SearchTestFixtures.java`
- T8. Guard seed edits with the seed-file test. Ref: `core/product/src/test/java/com/inventory/product/migration/VerticalSchemaSeedFilesTest.java`
- T9. Before the first test in a module without tests, add `spring-boot-starter-test` with `<scope>test</scope>`. Ref: `core/pricing/pom.xml`
  - Without the dependency or tests (8): `core/accounting/pom.xml`, `core/analytics/pom.xml`, `core/credit/pom.xml`, `core/metrics/pom.xml`, `core/notifications/pom.xml`, `core/plan/pom.xml`, `core/reminders/pom.xml`, `core/resource/pom.xml`. `plugins/grocery/pom.xml` has the dependency but no `src/test`. List: `grep -L spring-boot-starter-test core/*/pom.xml plugins/*/pom.xml`.

## Commands

Run from the repository root. Verified on 2026-10-17, commit `c00e7ef5`, Temurin 21.0.12.1, Maven 3.9.9, MongoDB 7.0.14.

| # | Command | Purpose | Verified result |
|---|---|---|---|
| 1 | `mvn -pl app -am clean test` | CI build and unit tests | PASS, 0 failures, 0 errors; 465 tests reported; about 85 s |
| 2 | `mvn test` | all modules' unit tests | PASS, 0 failures, 0 errors; 465 tests reported |
| 3 | `mvn -pl core/product -am test -Dtest='*Properties' -Dsurefire.failIfNoSpecifiedTests=false` | jqwik property tests | PASS, 0 failures, 0 errors; 49 tests reported |
| 4 | `mvn -pl core/product -am test -Dtest='InventorySearch*IT' -Dsurefire.failIfNoSpecifiedTests=false -Dstockkart.test.mongo.uri=<mongodb uri of a throwaway DB>` | Mongo integration tests | PASS, 16 tests, 0 failures (MongoDB 7.0.14) |
| 5 | `mvn clean install -DskipTests` | build all jars | PASS (builds the app jar) |
| 6 | `mvn -pl core/product compile` | compile one module (after #5) | PASS |
| 7 | `mvn -pl app spring-boot:run` (after #5) | run locally; reads `app/.env` | PASS: `GET /actuator/health` returns `{"status":"UP"}`; needs a reachable MongoDB and C3 |
| 8 | `shellcheck -x -P SCRIPTDIR -s bash scripts/deploy/*.sh` | lint deploy scripts (CI hygiene) | PASS, no findings (shellcheck 0.10.0) |
| 9 | `actionlint` | lint workflows (CI hygiene) | PASS, no findings (actionlint 1.7.7) |
| 10 | `docker compose up --build` | run app container | not verified: the workspace Docker daemon is remote; `docker compose config --quiet` PASS |

Reported test totals vary by a few between runs (engine reporting, T5). There is no Java linter or formatter (no checkstyle, spotless or PMD).

- K1. Run the CI command before pushing. Ref: `.github/workflows/build-checker.yml`
- K2. When a change touches `*Properties` tests or Mongo queries, also run Commands #3 or #4. Ref: `core/product/pom.xml`

## Do and Don't

- X1. Do add a metric constant and a row in `docs/metrics.md` when emitting a new metric. Ref: `docs/metrics.md`
- X2. Do put `@Latency`, `@RecordRequestRate` and `@RecordStatusCodes(module = "<module>")` on every controller. Ref: `core/user/src/main/java/com/inventory/user/rest/controller/CustomerController.java`
  - Exception: `core/product/src/main/java/com/inventory/product/rest/controller/StockEntryEstimateController.java`.
- X3. Do register a public (unauthenticated) path in both `AuthenticationInterceptor.PUBLIC_ENDPOINTS`/`PUBLIC_ENDPOINT_PATTERNS` and `excludePathPatterns` in `CorsConfig`, and flag it in the PR. Ref: `app/src/main/java/com/inventory/app/interceptor/AuthenticationInterceptor.java`
- X4. Do update docs when behaviour changes (README.md "Pull requests" step 4); for user-visible endpoints that means the endpoint tables in README.md. Ref: `README.md`
- X5. Do write mappers with MapStruct in `<module>/mapper` as `@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)` (27 of 32 `*Mapper.java` are MapStruct; 18 use exactly this annotation). Ref: `core/user/src/main/java/com/inventory/user/mapper/VendorMapper.java`
  - Hand-written (5): `core/credit/src/main/java/com/inventory/credit/service/CreditMapper.java`, `core/accounting/src/main/java/com/inventory/accounting/service/AccountingMapper.java`, `core/accounting/src/main/java/com/inventory/accounting/service/JournalRequestMapper.java`, `core/product/src/main/java/com/inventory/product/service/vertical/CartLineSnapshotMapper.java`, `core/product/src/main/java/com/inventory/product/labels/VerticalValueTypeMapper.java`. List: `find . -name '*Mapper.java' -path '*/src/main/*' | xargs grep -L '@Mapper'`.
- X6. Don't inject repositories into controllers (L3). Ref: `core/user/src/main/java/com/inventory/user/rest/controller/CustomerController.java`
- X7. Don't throw `IllegalArgumentException` for user errors; it becomes 500 (E4). Ref: `core/user/src/main/java/com/inventory/user/validation/VendorValidator.java`
- X8. Don't rely on `@Transactional` (P8) or on `@Indexed` (P4). Ref: `core/product/src/main/java/com/inventory/product/migration/SearchIndexMigration.java`
- X9. Don't read `System.getenv` (C1). Ref: `core/product/src/main/java/com/inventory/product/config/PrintBridgeProperties.java`
- X10. Don't add a Maven plugin or dependency without a version unless the Spring Boot BOM manages it (S2). Ref: `core/product/pom.xml`
- X11. Don't log secrets; mask them as `AppApplication` masks the DB URI password. Ref: `app/src/main/java/com/inventory/app/AppApplication.java`
- X12. Don't commit `.env` (C4). Ref: `.github/workflows/ci-hygiene.yml`

## How to add a new endpoint end to end

Follow the vendor/customer chain in `core/user`.

- A1. Pick the owning module and check the pom edge exists (L1). Ref: `core/user/pom.xml`
- A2. Add request and response DTOs (N3); the response here is `VendorDto`. Ref: `core/user/src/main/java/com/inventory/user/rest/dto/request/CreateVendorRequest.java`
- A3. Add the model and repository if the data is new (P1–P4). Ref: `core/user/src/main/java/com/inventory/user/domain/repository/VendorRepository.java`
- A4. Add or extend the validator (E6). Ref: `core/user/src/main/java/com/inventory/user/validation/VendorValidator.java`
- A5. Add or extend the mapper (X5). Ref: `core/user/src/main/java/com/inventory/user/mapper/VendorMapper.java`
- A6. Write the service method: shopId check → validator → mapper → repository → `metrics.record(...)`. Rethrow `ValidationException`; wrap `DataAccessException` in `BaseException(ErrorCode.INTERNAL_SERVER_ERROR, …)`. Ref: `core/user/src/main/java/com/inventory/user/service/VendorService.java`
- A7. Add the metric constant and the `docs/metrics.md` row (N7, X1). Ref: `core/user/src/main/java/com/inventory/user/utils/constants/UserMetricsConstants.java`
- A8. Add the controller method: path per N4, metrics annotations (X2), `shopId` from the request attribute (L6), `ResponseEntity.ok(ApiResponse.success(...))`. Ref: `core/user/src/main/java/com/inventory/user/rest/controller/CustomerController.java`
- A9. Check access: RBAC module prefix (automatic under `/api/v1/accounting`, `/api/v1/analytics`, `/api/v1/mis`, `/api/v1/taxation`), the plan-expiry allow-list, and the public list (X3). Ref: `app/src/main/java/com/inventory/app/interceptor/RbacModuleInterceptor.java`
- A10. Add tests (T2, T3), run K1, then run the app (Commands #7) and call the endpoint with a Bearer token. For a frontend-facing contract change, follow handbook API_CONTRACT.md (planned) and merge the backend first. Ref: `docs/deploying.md`
