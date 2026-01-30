"""
Invoice image preprocessing module.
Use before sending images to OpenAI for OCR.
"""

from .preprocess import (
    JPEG_QUALITY,
    MAX_DIMENSION,
    preprocess,
)

__all__ = ["preprocess", "MAX_DIMENSION", "JPEG_QUALITY"]
