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
    applyPartyChildren(v, true);
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

  /** Estimate / basic-bill template — shared by BASIC bills and Scan & Sell estimates. */
  public static InvoiceFieldVisibility basicFields() {
    InvoiceFieldVisibility v = new InvoiceFieldVisibility();
    // Parties: shop + customer identity on by default; compliance optional
    v.setShowSellerDetails(true);
    v.setShowShopName(true);
    v.setShowShopAddress(true);
    v.setShowShopTagline(false);
    v.setShowShopPhone(true);
    v.setShowShopEmail(false);
    v.setShowShopGstin(false);
    v.setShowShopPan(false);
    v.setShowShopDlNo(false);
    v.setShowShopFssai(false);
    v.setShowBuyerDetails(true);
    v.setShowCustomerName(true);
    v.setShowCustomerAddress(true);
    v.setShowCustomerPhone(true);
    v.setShowCustomerEmail(false);
    v.setShowCustomerGstin(false);
    v.setShowCustomerPan(false);
    v.setShowCustomerDlNo(false);
    // Quotes hide payment at print time for DocumentType.ESTIMATE; BASIC completed bills may show it
    v.setShowPaymentMethod(true);
    // Tax is forced off for BASIC billing; REGULAR estimates force tax on in InvoiceService
    v.setShowTaxDetails(false);
    v.setShowAmountInWords(true);
    v.setShowAmountSaved(true);
    v.setShowAdditionalDiscount(true);
    v.setShowHsn(false);
    v.setShowMfg(false);
    v.setShowExpiry(true);
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
    overlay(stored.getShowSellerDetails(), base::setShowSellerDetails);
    overlay(stored.getShowShopName(), base::setShowShopName);
    overlay(stored.getShowShopAddress(), base::setShowShopAddress);
    overlay(stored.getShowShopTagline(), base::setShowShopTagline);
    overlay(stored.getShowShopPhone(), base::setShowShopPhone);
    overlay(stored.getShowShopEmail(), base::setShowShopEmail);
    overlay(stored.getShowShopGstin(), base::setShowShopGstin);
    overlay(stored.getShowShopPan(), base::setShowShopPan);
    overlay(stored.getShowShopDlNo(), base::setShowShopDlNo);
    overlay(stored.getShowShopFssai(), base::setShowShopFssai);
    overlay(stored.getShowBuyerDetails(), base::setShowBuyerDetails);
    overlay(stored.getShowCustomerName(), base::setShowCustomerName);
    overlay(stored.getShowCustomerAddress(), base::setShowCustomerAddress);
    overlay(stored.getShowCustomerPhone(), base::setShowCustomerPhone);
    overlay(stored.getShowCustomerEmail(), base::setShowCustomerEmail);
    overlay(stored.getShowCustomerGstin(), base::setShowCustomerGstin);
    overlay(stored.getShowCustomerPan(), base::setShowCustomerPan);
    overlay(stored.getShowCustomerDlNo(), base::setShowCustomerDlNo);
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
    v.setShowShopName(src.getShowShopName());
    v.setShowShopAddress(src.getShowShopAddress());
    v.setShowShopTagline(src.getShowShopTagline());
    v.setShowShopPhone(src.getShowShopPhone());
    v.setShowShopEmail(src.getShowShopEmail());
    v.setShowShopGstin(src.getShowShopGstin());
    v.setShowShopPan(src.getShowShopPan());
    v.setShowShopDlNo(src.getShowShopDlNo());
    v.setShowShopFssai(src.getShowShopFssai());
    v.setShowBuyerDetails(src.getShowBuyerDetails());
    v.setShowCustomerName(src.getShowCustomerName());
    v.setShowCustomerAddress(src.getShowCustomerAddress());
    v.setShowCustomerPhone(src.getShowCustomerPhone());
    v.setShowCustomerEmail(src.getShowCustomerEmail());
    v.setShowCustomerGstin(src.getShowCustomerGstin());
    v.setShowCustomerPan(src.getShowCustomerPan());
    v.setShowCustomerDlNo(src.getShowCustomerDlNo());
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

  private static void applyPartyChildren(InvoiceFieldVisibility v, boolean on) {
    v.setShowShopName(on);
    v.setShowShopAddress(on);
    v.setShowShopTagline(on);
    v.setShowShopPhone(on);
    v.setShowShopEmail(on);
    v.setShowShopGstin(on);
    v.setShowShopPan(on);
    v.setShowShopDlNo(on);
    v.setShowShopFssai(on);
    v.setShowCustomerName(on);
    v.setShowCustomerAddress(on);
    v.setShowCustomerPhone(on);
    v.setShowCustomerEmail(on);
    v.setShowCustomerGstin(on);
    v.setShowCustomerPan(on);
    v.setShowCustomerDlNo(on);
  }

  private static void overlay(Boolean stored, java.util.function.Consumer<Boolean> setter) {
    if (stored != null) {
      setter.accept(stored);
    }
  }
}
