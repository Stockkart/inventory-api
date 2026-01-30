# Invoice image preprocessing

Preprocesses invoice images before sending to OpenAI OCR:

- **Auto rotate** — EXIF orientation + transpose
- **Crop invoice rectangle** — Detect document contour, remove background
- **Deskew** — Correct slant using min-area rect angle
- **Resize** — Longest side max 1280px
- **JPEG** — Quality 70

## Install

```bash
cd image_preprocess
pip install -r requirements.txt
```

## Use as library

```python
from image_preprocess import preprocess

# From file path
jpeg_bytes = preprocess("invoice.jpg")

# From bytes (e.g. upload)
jpeg_bytes = preprocess(image_bytes)

# Optional: disable crop/deskew, change size/quality
jpeg_bytes = preprocess("invoice.jpg", max_dimension=1024, jpeg_quality=80, crop_invoice=False)
```

## CLI (used by Java subprocess mode)

```bash
# From project root so "python -m image_preprocess" resolves
cd /path/to/inventory-api
python3 -m image_preprocess input.jpg output.jpg

# Stdin (bytes) → stdout (bytes) — Java sends image on stdin, reads JPEG from stdout
cat input.jpg | python3 -m image_preprocess > output.jpg
```

Options: `--max-dimension`, `--quality`, `--no-crop`, `--no-deskew`.

## HTTP API (used by Java when ocr.preprocess.mode=http)

Run the server, then point Java at its `/preprocess` endpoint:

```bash
# From project root
cd /path/to/inventory-api
PYTHONPATH=. uvicorn image_preprocess.server:app --host 0.0.0.0 --port 8081
```

- **POST /preprocess** — Request body: raw image bytes. Response: JPEG bytes (`Content-Type: image/jpeg`).
- **GET /health** — Health check.

In `application.properties`:

```properties
ocr.preprocess.mode=http
ocr.preprocess.url=http://localhost:8081/preprocess
```

## Docker

Build and run the HTTP API as a container (build from **repo root**):

```bash
# From inventory-api/
docker build -f image_preprocess/Dockerfile . -t image-preprocess
docker run -p 8081:8081 image-preprocess
```

- **POST /preprocess** — body: raw image bytes → response: JPEG bytes.
- **GET /health** — health check.

With **docker-compose**, the `image-preprocess` service is defined next to the app. Set env (or `application.properties`) so the Java app uses it when `ocr.provider=chatgpt`:

- `ocr.preprocess.mode=http`
- `ocr.preprocess.url=http://image-preprocess:8081/preprocess` (use service name when both run in Docker)
