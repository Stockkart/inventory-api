package com.inventory.ocr.prompt;

/**
 * Which invoice price columns the vision model should read.
 * Selected from shop type in the product layer — this module does not know {@code ShopType}.
 */
public enum InvoicePricingLayout {
  /** PTS → costPrice, PTR → priceToRetail, MRP → maximumRetailPrice. */
  WHOLESALER(new WholesalerInvoiceOcrPrompt()),
  /** Price/Unit → costPrice, MRP → maximumRetailPrice and priceToRetail (selling = MRP). */
  RETAILER(new RetailerInvoiceOcrPrompt());

  private final InvoiceOcrPrompt prompt;

  InvoicePricingLayout(InvoiceOcrPrompt prompt) {
    this.prompt = prompt;
  }

  public InvoiceOcrPrompt prompt() {
    return prompt;
  }

  public String promptText() {
    return prompt.text();
  }

  public static InvoicePricingLayout orDefault(InvoicePricingLayout layout) {
    return layout != null ? layout : WHOLESALER;
  }
}
