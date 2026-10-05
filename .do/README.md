# DigitalOcean App Platform — production backend

Production runs on a DigitalOcean App Platform app that pulls the backend image
from Docker Hub. The pipeline deploys by changing **only the image tag** in the
app's spec to the commit sha being released (`scripts/deploy/do-deploy.sh`).

## Why the app spec is not committed here

`PUT /v2/apps/{id}` replaces the whole spec. A committed copy would therefore
have to carry every environment variable, including secrets (DigitalOcean emits
them as encrypted `EV[1:…]` blobs), and this repository is public. Keeping the
spec only in DigitalOcean also avoids two copies drifting apart. The deploy
script fetches the live spec, patches the tag, and sends it straight back, so
nothing else can change as a side effect of a deploy (the script refuses to
continue if anything but the tag differs).

## What the spec is expected to look like

Exactly one service whose image is the backend:

```yaml
name: inventory-api
region: ...
services:
  - name: api
    image:
      registry_type: DOCKER_HUB
      registry: myntrack              # Docker Hub namespace
      repository: inventory-backend   # do-deploy.sh selects the service by this value (DO_IMAGE_REPOSITORY)
      tag: <40-char commit sha>       # set by the pipeline; never "latest"
    http_port: 8080
    instance_size_slug: ...           # ≥ 1 GB RAM (see Dockerfile JVM flags)
    instance_count: 1
    health_check:
      http_path: /actuator/health
      initial_delay_seconds: 60
    envs:
      - key: SPRING_PROFILES_ACTIVE
        value: prod
      # ... see "Environment variables" below
```

Anything else in the spec (domains, alerts, instance size) is managed in the
DigitalOcean console and left untouched by deploys.

## Environment variables the backend needs in production

Authoritative list: `app/src/main/resources/application.properties` and
`.env.example`. The ones without defaults, which must be present:

| Variable | Notes |
|---|---|
| `DB_URI` | MongoDB Atlas URI for the **production** database (type: SECRET) |
| `CLIENT_URL` | exact origin of the production frontend (single CORS origin) |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `OCR_PROVIDER`, `OCR_OPENAI_MODEL`, `OCR_GEMINI_MODEL`, `OCR_PREPROCESS_MODE`, `OCR_PREPROCESS_URL`, `OCR_PREPROCESS_PYTHON_PATH`, `OCR_PREPROCESS_WORKING_DIR` | OCR configuration |
| `OPENAI_API_KEY`, `GEMINI_API_KEY`, `AWS_ACCESS_KEY`, `AWS_SECRET_ACCESS`, `AWS_REGION` | provider credentials (SECRET) |
| `UPLOAD_TOKEN_EXPIRY_MINUTES` | |
| `RESEND_API_KEY`, `RESEND_FROM_EMAIL`, `RESEND_FROM_NAME` | email (SECRET for the key) |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` | payments (SECRET) |
| `GRAFANA_CLOUD_*` | optional observability, see README "Grafana Cloud" |

Staging (Render) needs the same set, pointing at the **staging** database and
the staging frontend origin.

## Deploying by hand

Same script the workflow uses:

```bash
export DIGITALOCEAN_TOKEN=... DO_APP_ID=...
scripts/deploy/do-deploy.sh <full-commit-sha>
scripts/deploy/verify-backend.sh https://<prod-api-host> <full-commit-sha> 15
```

Rollback is the same command with the previous sha (printed in every production
deploy summary).
