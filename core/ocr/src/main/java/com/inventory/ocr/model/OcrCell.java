package com.inventory.ocr.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single cell in an OCR table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrCell {
  /**
   * The text content of the cell.
   */
  private String text;

  /**
   * Row index (1-based).
   */
  private Integer rowIndex;

  /**
   * Column index (1-based).
   */
  private Integer columnIndex;

  /**
   * Whether this cell is a header cell.
   */
  private Boolean isHeader;

  /**
   * Confidence score for cell detection (0-100).
   */
  private Double confidence;
}
