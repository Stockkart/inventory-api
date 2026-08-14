package com.inventory.ocr.prompt;

/**
 * Shared JSON envelope, quantity, pack, and date rules. Subclasses only vary pricing and tax.
 */
abstract class AbstractInvoiceOcrPrompt implements InvoiceOcrPrompt {

  private static final String TEMPLATE = """
      Extract ONLY product line items from the invoice image.

      Return ONLY valid JSON: a raw array like [{"name":"...","count":1,...},...]
      or an object {"items":[...]}. No markdown, no code blocks, no extra text.
      Each object MUST have these keys:
      barcode, name, description, companyName, maximumRetailPrice, costPrice, priceToRetail, additionalDiscount,
      businessType, location, count, thresholdCount, expiryDate, reminderAt, customReminders, hsn, batchNo, scheme,
      pack, baseUnit, unitsPerPack, sgst, cgst.

      Rules:
      - Missing fields => null.
      - customReminders MUST ALWAYS be [] (never null).
      - barcode must be null unless the invoice explicitly shows Barcode/EAN/UPC.
      - name: Product column ONLY (e.g. PULMICUS-600 TAB). Do NOT prepend Pack text to name.
      - pack: copy Pack/Pkg/Packaging column exactly (e.g. "1*10", "1*15", "15*15", "1*100ML", "1").
      - baseUnit: GST-style unit if obvious from pack suffix or product (TAB/TABS→TBS, ML→MLT, CAPS→PCS); else null.
      - unitsPerPack: numeric base units per one pack from pack (1*10 → 10; 15*15 → 225; lone "1" → null).
      - Numbers must be numeric (not strings).
      - hsn must be copied exactly as printed (usually 8 digits).

      Quantity (count):
      - count must come from the quantity field of the product row (Qty/Quantity/Units/Nos/Count).
      - Do NOT use serial number / line number as count.
      - Do NOT use Size (XL/S/M/L) as count.
      - If quantity is not given but pack detail like "3 x 56" exists, set count = 3*56.

      Dates:
      - expiryDate must come ONLY from expiry/exp field (not mfg date).
      - Use ISO UTC like 2027-10-01T00:00:00Z. If month-year only, use first day of month.
      - reminderAt: ISO-8601 UTC; if month-year only use first day of month.

      Pricing:
      {{PRICING}}

      Tax:
      {{TAX}}

      Other:
      - Do not guess or calculate values from totals. Copy values only from the same product row.
      """;

  @Override
  public final String text() {
    return TEMPLATE
        .replace("{{PRICING}}", pricingRules().strip())
        .replace("{{TAX}}", taxRules().strip());
  }

  /** Column mapping for cost / PTR / MRP on this bill layout. */
  protected abstract String pricingRules();

  protected String taxRules() {
    return """
        - sgst/cgst must be rate only like "2.5". Ignore totals/tax summary rows.
        """;
  }
}
