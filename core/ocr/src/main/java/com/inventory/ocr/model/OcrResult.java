package com.inventory.ocr.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Provider-agnostic model representing OCR analysis results.
 * This model abstracts away provider-specific details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrResult {
  /**
   * List of tables extracted from the document.
   */
  private List<OcrTable> tables;

  /**
   * All text extracted from the document (for backward compatibility).
   */
  private String fullText;

  /**
   * Number of pages in the document.
   */
  private Integer pageCount;

  /**
   * Provider-specific metadata (optional).
   */
  private String providerMetadata;
}
