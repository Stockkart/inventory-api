package com.inventory.ocr.preprocess;

import lombok.extern.slf4j.Slf4j;

/**
 * Pass-through preprocessor: returns input unchanged.
 * Use when ocr.preprocess.mode=none or when no Python/service is available.
 */
@Slf4j
public class NoOpImagePreprocessor implements ImagePreprocessor {

  @Override
  public byte[] preprocess(byte[] imageBytes) {
    log.debug("Image preprocess disabled (no-op), passing through {} bytes", imageBytes.length);
    return imageBytes;
  }
}
