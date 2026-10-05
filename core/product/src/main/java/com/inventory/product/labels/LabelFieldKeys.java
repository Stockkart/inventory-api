package com.inventory.product.labels;

import java.util.Set;

/**
 * Stable {@code fieldKey} constants for every static (non-vertical) catalog field, plus the key
 * prefixes used for dynamically generated fields (label Req 1.6, 1.7; card Req 1.3).
 *
 * <p>Keys are part of the API contract: they are persisted in {@code shop_barcode_label_layouts}
 * and {@code shop_card_layouts} and exchanged with the frontend, so they must never be renamed.
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
  /** Card only: item type label (Normal / Costly / Temp N°). */
  public static final String ITEM_TYPE = "itemType";
  /** Card only: discount applicability label (Discount / Scheme / Discount + scheme). */
  public static final String DISCOUNT_APPLICABLE = "discountApplicable";

  // ---- pricing group -------------------------------------------------------------------------
  public static final String MRP = "mrp";
  public static final String SELLING_PRICE = "sellingPrice";
  public static final String PTR = "ptr";
  public static final String COST_PRICE = "costPrice";
  public static final String SALE_SCHEME = "saleScheme";
  public static final String GST_RATE = "gstRate";
  /** Card only: additional discount offered on sale (%). */
  public static final String SALE_ADDITIONAL_DISCOUNT = "saleAdditionalDiscount";
  /** Card only, shop-internal: additional discount received on purchase (%). */
  public static final String PURCHASE_ADDITIONAL_DISCOUNT = "purchaseAdditionalDiscount";
  /** Card only, shop-internal: scheme received on purchase. */
  public static final String PURCHASE_SCHEME = "purchaseScheme";
  /** Card only, shop-internal: cost after purchase discounts and schemes. */
  public static final String EFFECTIVE_COST_PRICE = "effectiveCostPrice";

  /** Prefix for named-rate fields: {@code pricing.rate.<rateName>} (rate name used verbatim). */
  public static final String PRICING_RATE_PREFIX = "pricing.rate.";

  // ---- lot group -----------------------------------------------------------------------------
  public static final String BATCH_NO = "batchNo";
  public static final String EXPIRY_DATE = "expiryDate";
  public static final String RECEIVED_DATE = "receivedDate";
  /** Card only: storage location of the lot. */
  public static final String LOCATION = "location";
  /** Card only: stock available to sell (current minus open-quotation reservations). */
  public static final String AVAILABLE_COUNT = "availableCount";
  /** Card only: physical stock on hand. */
  public static final String CURRENT_COUNT = "currentCount";
  /** Card only: quantity received into the lot. */
  public static final String RECEIVED_COUNT = "receivedCount";
  /** Card only: quantity sold from the lot. */
  public static final String SOLD_COUNT = "soldCount";
  /** Card only: low-stock threshold. */
  public static final String THRESHOLD_COUNT = "thresholdCount";
  /** Card only: purchase date of the lot. */
  public static final String PURCHASE_DATE = "purchaseDate";
  /** Search only (computed): IN_STOCK / LOW_STOCK / SOLD_OUT from current and threshold counts. */
  public static final String STOCK_STATE = "stockState";
  /** Card and search: billing mode of the lot (REGULAR / BASIC). */
  public static final String BILLING_MODE = "billingMode";

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
