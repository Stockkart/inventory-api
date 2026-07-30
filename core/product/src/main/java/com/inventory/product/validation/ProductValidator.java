package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

@Component
public class ProductValidator {

  /** Code128-friendly shop barcodes: printable ASCII, reasonable length. */
  private static final Pattern BARCODE_FORMAT = Pattern.compile("^[A-Za-z0-9._\\-]{4,64}$");

  /**
   * Validates barcode format when present. Empty/null is allowed (optional field).
   */
  public void validateBarcode(String barcode) {
    if (!StringUtils.hasText(barcode)) {
      return;
    }
    String trimmed = barcode.trim();
    if (!BARCODE_FORMAT.matcher(trimmed).matches()) {
      throw new ValidationException(
          "Barcode must be 4–64 characters: letters, digits, dot, underscore, or hyphen");
    }
  }

  /** Normalize empty barcode to null; trim non-empty. */
  public String normalizeBarcode(String barcode) {
    if (!StringUtils.hasText(barcode)) {
      return null;
    }
    return barcode.trim();
  }
}
