# Stock Kart – Inventory API

Backend API for the Stock Kart inventory management system. A Spring Boot–based multi-module application with MongoDB, supporting inventory, billing, plans, OCR invoice parsing, and extensible plugins.

---

## Table of Contents

- [Project Overview](#project-overview)
- [Local Setup with Docker](#local-setup-with-docker)
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
- **Notifications** – Low-stock alerts, reminders
- **Analytics** – Customer analytics, dashboards
- **Plugin engine** – Extensible domain plugins (e.g. medical)

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
│   ├── notifications/   # Events, reminders
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
| **core/notifications** | Events, reminders, low-stock alerts |
| **core/analytics** | Customer analytics and reporting |
| **core/taxation** | Tax calculation logic |
| **pluginengine** | Plugin discovery and registration |
| **plugins/medical** | Medical-domain extensions |

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
