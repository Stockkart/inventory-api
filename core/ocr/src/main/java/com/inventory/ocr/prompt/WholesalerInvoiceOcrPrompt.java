package com.inventory.ocr.prompt;

/**
 * Pharma wholesaler / distributor purchase bills: PTS, PTR, and MRP as distinct columns.
 */
final class WholesalerInvoiceOcrPrompt extends AbstractInvoiceOcrPrompt {

  @Override
  protected String pricingRules() {
    return """
        - This is a wholesaler/distributor invoice with PTS, PTR, and MRP.
        - maximumRetailPrice must come from MRP field.
          If Reduced MRP / discounted MRP exists, use Reduced MRP as maximumRetailPrice.
        - priceToRetail must come from retail selling price field (PTR / Price to Retail / Selling Price / Retail Price).
        - costPrice must come from unit purchase price field (Rate / Cost Price / PTS / Price to Stockist).
        - priceToRetail and costPrice must NEVER be taken from MRP or Reduced MRP.
        - Do NOT use taxable amount / SGST value / CGST value / totals as costPrice or priceToRetail.
        - If multiple unit price numbers exist in the same product row, choose:
          costPrice = lowest unit price,
          priceToRetail = next higher unit price,
          maximumRetailPrice = highest unit price (or Reduced MRP if present).
        """;
  }
}
