"""
HTTP API for invoice image preprocessing.
POST /preprocess: multipart/form-data with key "image" (file); response = JPEG bytes.

Run:
uvicorn image_preprocess.server:app --host 0.0.0.0 --port 8081
"""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, UploadFile
from fastapi.responses import Response

from .preprocess import preprocess

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Image preprocess API started")
    yield
    logger.info("Image preprocess API shutting down")


app = FastAPI(title="Invoice Image Preprocess", lifespan=lifespan)


@app.post("/preprocess", response_class=Response)
async def preprocess_image(image: UploadFile = File(..., alias="image")) -> Response:
    try:
        body = await image.read()
        if not body:
            return Response(status_code=400, content=b"Empty body")

        out = preprocess(body)
        return Response(content=out, media_type="image/jpeg")

    except Exception as e:
        logger.exception("Preprocess failed")
        return Response(status_code=500, content=str(e).encode("utf-8"))


@app.get("/health")
async def health():
    return {"status": "ok"}
