package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response DTO for OCR text extraction.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OcrResponse {
  private String text;
}

