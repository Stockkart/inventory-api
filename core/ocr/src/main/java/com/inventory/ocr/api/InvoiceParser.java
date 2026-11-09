package com.inventory.ocr.api;

import java.util.List;

/** Reads inventory lines from a supplier invoice image with the configured OCR provider. */
public interface InvoiceParser {

  /**
   * @param layout which price columns to read; null means {@link InvoicePricingLayout#WHOLESALER}
   * @return the lines found, in invoice order
   */
  List<ParsedInvoiceItem> parseInvoiceImage(byte[] imageBytes, InvoicePricingLayout layout);
}
