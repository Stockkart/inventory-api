package com.inventory.ocr.prompt;

/**
 * Strategy for the vision-model instruction used to extract invoice line items.
 * Add a new implementation + {@link InvoicePricingLayout} constant to support another bill layout.
 */
public interface InvoiceOcrPrompt {

  /** Stable JSON contract and extraction rules sent to the vision model. */
  String text();
}
