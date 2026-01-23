package com.inventory.ocr.provider;

import com.inventory.ocr.model.OcrResult;

import java.io.IOException;

/**
 * Interface for OCR providers.
 * This abstraction allows easy swapping of OCR implementations (AWS Textract, Google Vision, etc.)
 */
public interface OcrProvider {

  /**
   * Analyze a document and extract structured data (tables, text, etc.).
   *
   * @param imageBytes the image file as byte array
   * @return OcrResult containing extracted tables and text
   * @throws IOException if image cannot be read or processing fails
   */
  OcrResult analyzeDocument(byte[] imageBytes) throws IOException;

  /**
   * Get the name/identifier of this OCR provider.
   *
   * @return provider name (e.g., "AWS_TEXTTRACT", "GOOGLE_VISION")
   */
  String getProviderName();
}
