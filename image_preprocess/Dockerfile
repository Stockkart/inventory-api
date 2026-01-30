# Invoice image preprocessing HTTP API (EXIF rotate, crop, deskew, resize, JPEG).
# Run: docker build -f image_preprocess/Dockerfile . -t image-preprocess
#      docker run -p 8081:8081 image-preprocess

FROM python:3.12-slim

WORKDIR /app

# OpenCV headless still links against libxcb on some platforms
RUN apt-get update && apt-get install -y --no-install-recommends \
    libxcb1 \
    && rm -rf /var/lib/apt/lists/*

# Copy module and install Python dependencies
COPY image_preprocess/ /app/image_preprocess/
RUN pip install --no-cache-dir -r /app/image_preprocess/requirements.txt

ENV PYTHONPATH=/app
ENV PYTHONUNBUFFERED=1

EXPOSE 8081

# HTTP API: POST /preprocess (body = image bytes, response = JPEG)
CMD ["uvicorn", "image_preprocess.server:app", "--host", "0.0.0.0", "--port", "8081"]
