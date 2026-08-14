package com.inventory.ocr.prompt;

/**
 * Retailer purchase bills (tax invoice from a wholesaler): unit cost + MRP, no PTR/PTS.
 */
final class RetailerInvoiceOcrPrompt extends AbstractInvoiceOcrPrompt {

  @Override
  protected String pricingRules() {
    return """
        - This is a retailer purchase invoice. Typical columns: Item, HSN, Batch, MRP, Size, Qty, Price/Unit, GST, Amount.
        - There is usually NO PTR or PTS column. Do not invent PTR from other numbers.
        - costPrice is the UNIT purchase price from Price/Unit, Rate, or Cost Price (ex-GST).
          Never use Amount, line total, Taxable total, Net, GST rupee amount, or MRP as costPrice.
          Never divide Amount by Quantity to invent costPrice.
        - maximumRetailPrice must come from the MRP column (Reduced MRP if present).
        - priceToRetail must be set to the same value as maximumRetailPrice.
          For retailers, selling price equals MRP.
        - Do NOT apply a lowest / mid / highest unit-price heuristic.
        """;
  }

  @Override
  protected String taxRules() {
    return """
        - sgst/cgst must be rate only like "2.5". Ignore totals/tax summary rows.
        - If the row shows a single GST percent (e.g. 5.0% or "36.57 (5.0%)") and no separate SGST/CGST:
          split equally (5.0 → sgst "2.5", cgst "2.5").
        """;
  }
}
