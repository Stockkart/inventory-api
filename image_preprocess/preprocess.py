"""
Invoice image preprocessing before sending to OpenAI.
- Auto rotate (EXIF)
- Crop invoice rectangle (remove background) with strong fallback
- Deskew (Hough lines based)
- Contrast/threshold cleanup
- Resize to max 1280px
- JPEG compress quality 70
"""

from __future__ import annotations

import io
import logging
from pathlib import Path
from typing import Union

import cv2
import numpy as np
from PIL import Image, ImageOps

logger = logging.getLogger(__name__)

MAX_DIMENSION = 1280
JPEG_QUALITY = 70


def preprocess(
        source: Union[bytes, Path, str],
        *,
        max_dimension: int = MAX_DIMENSION,
        jpeg_quality: int = JPEG_QUALITY,
        crop_invoice: bool = True,
        deskew: bool = True,
) -> bytes:
    pil_img = _load_image(source)
    pil_img = ImageOps.exif_transpose(pil_img).convert("RGB")

    img = np.array(pil_img)[:, :, ::-1].copy()  # RGB->BGR

    # 1) Basic cleanup first (helps crop)
    img = _force_portrait(img)
    img = _auto_contrast(img)

    # 2) Crop invoice
    # if crop_invoice:
    img = _crop_invoice(img)
    # img = _trim_edges(img, 0.015)

    # 3) Deskew after crop
    if deskew:
        img = _deskew_hough(img)

    # 4) Final mild cleanup for OCR
    img = _denoise_and_sharpen(img)

    # 5) Resize
    pil_out = Image.fromarray(img[:, :, ::-1])  # BGR->RGB
    pil_out = _resize_max(pil_out, max_dimension)

    # 6) Encode JPEG
    buf = io.BytesIO()
    pil_out.save(buf, format="JPEG", quality=jpeg_quality, optimize=True)
    return buf.getvalue()

def _trim_edges(img: np.ndarray, percent: float = 0.015) -> np.ndarray:
    h, w = img.shape[:2]
    dx = int(w * percent)
    dy = int(h * percent)
    return img[dy:h-dy, dx:w-dx]

def _force_portrait(img: np.ndarray) -> np.ndarray:
    """
    Try 4 rotations and pick the one that looks most like an upright invoice/table.
    """
    rotations = [
        img,
        cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE),
        cv2.rotate(img, cv2.ROTATE_180),
        cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE),
    ]

    scores = [ _orientation_score(x) for x in rotations ]
    best_idx = int(np.argmax(scores))

    logger.info(f"Orientation scores={scores}, best={best_idx}")
    return rotations[best_idx]


def _orientation_score(img: np.ndarray) -> float:
    """
    Score rotation by how many strong horizontal lines exist.
    Invoices have tables -> lots of horizontal lines when upright.
    """
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (3, 3), 0)

    edges = cv2.Canny(gray, 50, 150)

    lines = cv2.HoughLinesP(
        edges,
        rho=1,
        theta=np.pi / 180,
        threshold=120,
        minLineLength=int(min(img.shape[0], img.shape[1]) * 0.25),
        maxLineGap=10,
    )

    if lines is None:
        return 0.0

    horizontal = 0
    total_len = 0.0

    for x1, y1, x2, y2 in lines[:, 0]:
        dx = abs(x2 - x1)
        dy = abs(y2 - y1)

        # horizontal-ish lines
        if dx > dy * 3:
            horizontal += 1
            total_len += dx

    return horizontal * 10 + total_len



def _load_image(source: Union[bytes, Path, str]) -> Image.Image:
    if isinstance(source, bytes):
        return Image.open(io.BytesIO(source)).copy()
    path = Path(source)
    if not path.exists():
        raise FileNotFoundError(f"Image not found: {path}")
    return Image.open(path).copy()


# ----------------------------
# Enhancement helpers
# ----------------------------

def _auto_contrast(img: np.ndarray) -> np.ndarray:
    """CLAHE contrast boost helps faded invoices + shadows."""
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=2.5, tileGridSize=(8, 8))
    l2 = clahe.apply(l)
    out = cv2.merge((l2, a, b))
    return cv2.cvtColor(out, cv2.COLOR_LAB2BGR)


def _denoise_and_sharpen(img: np.ndarray) -> np.ndarray:
    """Light denoise + unsharp mask."""
    den = cv2.fastNlMeansDenoisingColored(img, None, 6, 6, 7, 21)
    blur = cv2.GaussianBlur(den, (0, 0), 1.0)
    sharp = cv2.addWeighted(den, 1.5, blur, -0.5, 0)
    return sharp


# ----------------------------
# Cropping (document detection)
# ----------------------------

def _crop_invoice(img: np.ndarray) -> np.ndarray:
    """
    Document crop with strong fallback:
    1) Try 4-point contour document detection.
    2) If not found, try bounding rectangle of the largest contour.
    3) If still not found, return original.
    """
    h, w = img.shape[:2]

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (5, 5), 0)

    # adaptive threshold works better than Canny for wrinkled pages
    thr = cv2.adaptiveThreshold(
        gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY, 31, 5
    )
    thr = cv2.bitwise_not(thr)

    # connect edges/borders
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
    mor = cv2.morphologyEx(thr, cv2.MORPH_CLOSE, kernel, iterations=2)

    contours, _ = cv2.findContours(mor, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        logger.debug("No contours found for crop")
        return img

    contours = sorted(contours, key=cv2.contourArea, reverse=True)

    # attempt 4-corner crop
    for cnt in contours[:8]:
        area = cv2.contourArea(cnt)
        if area < 0.10 * (w * h):
            break

        peri = cv2.arcLength(cnt, True)
        approx = cv2.approxPolyDP(cnt, 0.02 * peri, True)

        if len(approx) == 4:
            return _four_point_crop(img, approx.reshape(4, 2))

    # fallback: largest contour bounding box crop
    x, y, bw, bh = cv2.boundingRect(contours[0])
    if bw * bh > 0.20 * (w * h):
        pad = int(0.01 * max(w, h))
        x2 = max(0, x - pad)
        y2 = max(0, y - pad)
        x3 = min(w, x + bw + pad)
        y3 = min(h, y + bh + pad)
        cropped = img[y2:y3, x2:x3]
        logger.debug("Fallback bounding crop used")
        return cropped

    logger.debug("Crop failed, using full image")
    return img


def _four_point_crop(img: np.ndarray, pts: np.ndarray) -> np.ndarray:
    rect = _order_points(pts)
    (tl, tr, br, bl) = rect

    width_a = np.linalg.norm(br - bl)
    width_b = np.linalg.norm(tr - tl)
    max_width = max(int(width_a), int(width_b))

    height_a = np.linalg.norm(tr - br)
    height_b = np.linalg.norm(tl - bl)
    max_height = max(int(height_a), int(height_b))

    # prevent tiny invalid crops
    max_width = max(50, max_width)
    max_height = max(50, max_height)

    dst = np.array(
        [[0, 0],
         [max_width - 1, 0],
         [max_width - 1, max_height - 1],
         [0, max_height - 1]],
        dtype=np.float32,
    )

    M = cv2.getPerspectiveTransform(rect.astype(np.float32), dst)
    warped = cv2.warpPerspective(img, M, (max_width, max_height))
    return warped


def _order_points(pts: np.ndarray) -> np.ndarray:
    rect = np.zeros((4, 2), dtype=np.float32)
    s = pts.sum(axis=1)
    rect[0] = pts[np.argmin(s)]
    rect[2] = pts[np.argmax(s)]
    diff = np.diff(pts, axis=1)
    rect[1] = pts[np.argmin(diff)]
    rect[3] = pts[np.argmax(diff)]
    return rect


# ----------------------------
# Deskew (Hough lines based)
# ----------------------------

def _deskew_hough(img: np.ndarray) -> np.ndarray:
    """
    Robust deskew using Hough line angles.
    Better than minAreaRect for invoices/tables.
    """
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 50, 150, apertureSize=3)

    lines = cv2.HoughLines(edges, 1, np.pi / 180, 140)
    if lines is None:
        return img

    angles = []
    for rho_theta in lines[:80]:
        rho, theta = rho_theta[0]
        angle = (theta * 180 / np.pi) - 90
        # keep only near-horizontal lines (table lines)
        if -30 < angle < 30:
            angles.append(angle)

    if not angles:
        return img

    median_angle = float(np.median(angles))
    if abs(median_angle) < 0.4:
        return img

    h, w = img.shape[:2]
    center = (w // 2, h // 2)
    M = cv2.getRotationMatrix2D(center, median_angle, 1.0)
    rotated = cv2.warpAffine(img, M, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    return rotated


def _resize_max(pil_img: Image.Image, max_dimension: int) -> Image.Image:
    w, h = pil_img.size
    if max(w, h) <= max_dimension:
        return pil_img
    scale = max_dimension / max(w, h)
    nw = max(1, round(w * scale))
    nh = max(1, round(h * scale))
    return pil_img.resize((nw, nh), Image.Resampling.LANCZOS)
