package com.inventory.product.labels;

import java.util.Set;

/**
 * Stable {@code fieldKey} constants for every static (non-vertical) printable field, plus the key
 * prefixes used for dynamically generated fields (Req 1.6, 1.7).
 *
 * <p>Keys are part of the API contract: they are persisted in {@code shop_barcode_label_layouts}
 * and exchanged with the frontend, so they must never be renamed.
 */
public final class LabelFieldKeys {

  // ---- product group -------------------------------------------------------------------------
  public static final String PRODUCT_NAME = "productName";
  public static final String COMPANY_NAME = "companyName";
  public static final String BARCODE_TEXT = "barcodeText";
  public static final String HSN = "hsn";
  public static final String BASE_UNIT = "baseUnit";
  public static final String PACK_SIZE = "packSize";
  public static final String DESCRIPTION = "description";

  // ---- pricing group -------------------------------------------------------------------------
  public static final String MRP = "mrp";
  public static final String SELLING_PRICE = "sellingPrice";
  public static final String PTR = "ptr";
  public static final String COST_PRICE = "costPrice";
  public static final String SALE_SCHEME = "saleScheme";
  public static final String GST_RATE = "gstRate";

  /** Prefix for named-rate fields: {@code pricing.rate.<rateName>} (rate name used verbatim). */
  public static final String PRICING_RATE_PREFIX = "pricing.rate.";

  // ---- lot group -----------------------------------------------------------------------------
  public static final String BATCH_NO = "batchNo";
  public static final String EXPIRY_DATE = "expiryDate";
  public static final String RECEIVED_DATE = "receivedDate";

  // ---- shop group ----------------------------------------------------------------------------
  public static final String SHOP_NAME = "shopName";
  public static final String SHOP_TAGLINE = "shopTagline";
  public static final String SHOP_PHONE = "shopPhone";
  public static final String SHOP_EMAIL = "shopEmail";
  public static final String SHOP_ADDRESS = "shopAddress";
  public static final String SHOP_GSTIN = "shopGstin";
  public static final String SHOP_FSSAI = "shopFssai";
  public static final String SHOP_DL_NO = "shopDlNo";

  // ---- vertical group ------------------------------------------------------------------------
  /** Prefix for vertical schema fields: {@code vertical.<schemaKey>}. */
  public static final String VERTICAL_PREFIX = "vertical.";

  /**
   * Backing property names (schema {@code apiKey} / {@code key}) of the core product and lot
   * fields. A vertical schema field whose key matches one of these, case-sensitively, is already
   * printable through a core/lot field and is therefore skipped when building the vertical set
   * (Req 1.6).
   */
  public static final Set<String> CORE_LOT_BACKING_PROPERTIES =
      Set.of(
          "name",
          "companyName",
          "barcode",
          "hsn",
          "baseUnit",
          "unitConversions",
          "description",
          "batchNo",
          "expiryDate",
          "receivedDate");

  private LabelFieldKeys() {}

  /** Builds the {@code fieldKey} of a named pricing rate. */
  public static String pricingRateKey(String rateName) {
    return PRICING_RATE_PREFIX + rateName;
  }

  /** Builds the {@code fieldKey} of a vertical schema field. */
  public static String verticalKey(String schemaKey) {
    return VERTICAL_PREFIX + schemaKey;
  }

  /** True when the key denotes a named pricing rate field. */
  public static boolean isPricingRateKey(String fieldKey) {
    return fieldKey != null
        && fieldKey.length() > PRICING_RATE_PREFIX.length()
        && fieldKey.startsWith(PRICING_RATE_PREFIX);
  }

  /** True when the key denotes a vertical schema field. */
  public static boolean isVerticalKey(String fieldKey) {
    return fieldKey != null
        && fieldKey.length() > VERTICAL_PREFIX.length()
        && fieldKey.startsWith(VERTICAL_PREFIX);
  }
}
