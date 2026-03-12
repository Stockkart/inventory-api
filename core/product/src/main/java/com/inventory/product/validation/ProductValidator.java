package com.inventory.product.validation;

import org.springframework.stereotype.Component;

@Component
public class ProductValidator {

  /**
   * Validates barcode if needed. Barcode is optional - no validation when null or empty.
   */
  public void validateBarcode(String barcode) {
    // Barcode is optional; no validation required
  }
}
