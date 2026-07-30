package com.inventory.product.domain.model;

/**
 * Built-in invoice field defaults matching pre-settings PDF behavior.
 */
public final class InvoiceSettingsDefaults {

  public static final String DEFAULT_PRINTER_TYPE = "NORMAL";

  private InvoiceSettingsDefaults() {}

  /** Tax invoice (REGULAR) — show seller/buyer/tax/scheme and typical pharmacy columns. */
  public static InvoiceFieldVisibility regularFields() {
    InvoiceFieldVisibility v = new InvoiceFieldVisibility();
    v.setShowSellerDetails(true);
    v.setShowBuyerDetails(true);
    v.setShowPaymentMethod(true);
    v.setShowTaxDetails(true);
    v.setShowAmountInWords(true);
    v.setShowAmountSaved(true);
    v.setShowAdditionalDiscount(true);
    v.setShowHsn(true);
    v.setShowMfg(true);
    v.setShowExpiry(true);
    v.setShowBatch(true);
    v.setShowMrp(true);
    v.setShowScheme(true);
    v.setShowLineDiscount(true);
    v.setShowSignatures(true);
    return v;
  }

  /** Estimate / BASIC — hide tax and party compliance details. */
  public static InvoiceFieldVisibility basicFields() {
    InvoiceFieldVisibility v = new InvoiceFieldVisibility();
    v.setShowSellerDetails(false);
    v.setShowBuyerDetails(false);
    v.setShowPaymentMethod(true);
    v.setShowTaxDetails(false);
    v.setShowAmountInWords(true);
    v.setShowAmountSaved(true);
    v.setShowAdditionalDiscount(true);
    v.setShowHsn(false);
    v.setShowMfg(false);
    v.setShowExpiry(false);
    v.setShowBatch(true);
    v.setShowMrp(true);
    v.setShowScheme(false);
    v.setShowLineDiscount(true);
    v.setShowSignatures(false);
    return v;
  }

  public static ShopInvoiceSettingsDocument unsavedDefaults(String shopId) {
    ShopInvoiceSettingsDocument doc = new ShopInvoiceSettingsDocument();
    doc.setShopId(shopId);
    doc.setDefaultPrinterType(DEFAULT_PRINTER_TYPE);
    doc.setFooterNote("");
    doc.setRegularFields(regularFields());
    doc.setBasicFields(basicFields());
    return doc;
  }

  /**
   * Merge stored (possibly partial) visibility over mode defaults.
   * Null stored fields keep the default value.
   */
  public static InvoiceFieldVisibility resolve(
      InvoiceFieldVisibility stored, InvoiceFieldVisibility defaults) {
    InvoiceFieldVisibility base = defaults != null ? copy(defaults) : regularFields();
    if (stored == null) {
      return base;
    }
    if (stored.getShowSellerDetails() != null) {
      base.setShowSellerDetails(stored.getShowSellerDetails());
    }
    if (stored.getShowBuyerDetails() != null) {
      base.setShowBuyerDetails(stored.getShowBuyerDetails());
    }
    if (stored.getShowPaymentMethod() != null) {
      base.setShowPaymentMethod(stored.getShowPaymentMethod());
    }
    if (stored.getShowTaxDetails() != null) {
      base.setShowTaxDetails(stored.getShowTaxDetails());
    }
    if (stored.getShowAmountInWords() != null) {
      base.setShowAmountInWords(stored.getShowAmountInWords());
    }
    if (stored.getShowAmountSaved() != null) {
      base.setShowAmountSaved(stored.getShowAmountSaved());
    }
    if (stored.getShowAdditionalDiscount() != null) {
      base.setShowAdditionalDiscount(stored.getShowAdditionalDiscount());
    }
    if (stored.getShowHsn() != null) {
      base.setShowHsn(stored.getShowHsn());
    }
    if (stored.getShowMfg() != null) {
      base.setShowMfg(stored.getShowMfg());
    }
    if (stored.getShowExpiry() != null) {
      base.setShowExpiry(stored.getShowExpiry());
    }
    if (stored.getShowBatch() != null) {
      base.setShowBatch(stored.getShowBatch());
    }
    if (stored.getShowMrp() != null) {
      base.setShowMrp(stored.getShowMrp());
    }
    if (stored.getShowScheme() != null) {
      base.setShowScheme(stored.getShowScheme());
    }
    if (stored.getShowLineDiscount() != null) {
      base.setShowLineDiscount(stored.getShowLineDiscount());
    }
    if (stored.getShowSignatures() != null) {
      base.setShowSignatures(stored.getShowSignatures());
    }
    return base;
  }

  public static InvoiceFieldVisibility copy(InvoiceFieldVisibility src) {
    if (src == null) {
      return null;
    }
    InvoiceFieldVisibility v = new InvoiceFieldVisibility();
    v.setShowSellerDetails(src.getShowSellerDetails());
    v.setShowBuyerDetails(src.getShowBuyerDetails());
    v.setShowPaymentMethod(src.getShowPaymentMethod());
    v.setShowTaxDetails(src.getShowTaxDetails());
    v.setShowAmountInWords(src.getShowAmountInWords());
    v.setShowAmountSaved(src.getShowAmountSaved());
    v.setShowAdditionalDiscount(src.getShowAdditionalDiscount());
    v.setShowHsn(src.getShowHsn());
    v.setShowMfg(src.getShowMfg());
    v.setShowExpiry(src.getShowExpiry());
    v.setShowBatch(src.getShowBatch());
    v.setShowMrp(src.getShowMrp());
    v.setShowScheme(src.getShowScheme());
    v.setShowLineDiscount(src.getShowLineDiscount());
    v.setShowSignatures(src.getShowSignatures());
    return v;
  }
}
