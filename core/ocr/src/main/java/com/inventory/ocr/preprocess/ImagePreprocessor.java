package com.inventory.ocr.preprocess;

import java.io.IOException;

/**
 * Preprocesses an invoice image before sending to OCR (e.g. EXIF rotate, crop, deskew, resize, JPEG).
 * Can be backed by a local Python subprocess or a remote HTTP service.
 */
public interface ImagePreprocessor {

  /**
   * Preprocess raw image bytes; returns JPEG bytes suitable for OpenAI vision.
   *
   * @param imageBytes raw image (any format the preprocessor supports)
   * @return preprocessed JPEG bytes
   * @throws IOException if preprocessing fails
   */
  byte[] preprocess(byte[] imageBytes) throws IOException;
}
