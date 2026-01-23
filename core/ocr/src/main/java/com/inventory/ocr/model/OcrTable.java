package com.inventory.ocr.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Represents a table extracted from OCR.
 * Provider-agnostic model that can be created from any OCR provider's response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrTable {
  /**
   * Table data organized by row and column indices.
   * Key: row index (1-based), Value: map of column index (1-based) to cell data
   */
  private Map<Integer, Map<Integer, OcrCell>> cells;

  /**
   * Total number of rows in the table.
   */
  private Integer rowCount;

  /**
   * Total number of columns in the table.
   */
  private Integer columnCount;

  /**
   * Confidence score for table detection (0-100).
   */
  private Double confidence;
}
