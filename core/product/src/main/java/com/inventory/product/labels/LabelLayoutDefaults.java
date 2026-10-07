package com.inventory.product.labels;

import static com.inventory.product.labels.LabelFieldKeys.AVAILABLE_COUNT;
import static com.inventory.product.labels.LabelFieldKeys.BARCODE_TEXT;
import static com.inventory.product.labels.LabelFieldKeys.BASE_UNIT;
import static com.inventory.product.labels.LabelFieldKeys.BATCH_NO;
import static com.inventory.product.labels.LabelFieldKeys.BILLING_MODE;
import static com.inventory.product.labels.LabelFieldKeys.COMPANY_NAME;
import static com.inventory.product.labels.LabelFieldKeys.COST_PRICE;
import static com.inventory.product.labels.LabelFieldKeys.CURRENT_COUNT;
import static com.inventory.product.labels.LabelFieldKeys.DESCRIPTION;
import static com.inventory.product.labels.LabelFieldKeys.DISCOUNT_APPLICABLE;
import static com.inventory.product.labels.LabelFieldKeys.EFFECTIVE_COST_PRICE;
import static com.inventory.product.labels.LabelFieldKeys.EXPIRY_DATE;
import static com.inventory.product.labels.LabelFieldKeys.GST_RATE;
import static com.inventory.product.labels.LabelFieldKeys.HSN;
import static com.inventory.product.labels.LabelFieldKeys.ITEM_TYPE;
import static com.inventory.product.labels.LabelFieldKeys.LOCATION;
import static com.inventory.product.labels.LabelFieldKeys.MRP;
import static com.inventory.product.labels.LabelFieldKeys.PACK_SIZE;
import static com.inventory.product.labels.LabelFieldKeys.PRODUCT_NAME;
import static com.inventory.product.labels.LabelFieldKeys.PTR;
import static com.inventory.product.labels.LabelFieldKeys.PURCHASE_ADDITIONAL_DISCOUNT;
import static com.inventory.product.labels.LabelFieldKeys.PURCHASE_DATE;
import static com.inventory.product.labels.LabelFieldKeys.PURCHASE_SCHEME;
import static com.inventory.product.labels.LabelFieldKeys.RECEIVED_COUNT;
import static com.inventory.product.labels.LabelFieldKeys.RECEIVED_DATE;
import static com.inventory.product.labels.LabelFieldKeys.SALE_ADDITIONAL_DISCOUNT;
import static com.inventory.product.labels.LabelFieldKeys.SALE_SCHEME;
import static com.inventory.product.labels.LabelFieldKeys.SELLING_PRICE;
import static com.inventory.product.labels.LabelFieldKeys.SHOP_ADDRESS;
import static com.inventory.product.labels.LabelFieldKeys.SHOP_DL_NO;
import static com.inventory.product.labels.LabelFieldKeys.SHOP_EMAIL;
import static com.inventory.product.labels.LabelFieldKeys.SHOP_FSSAI;
import static com.inventory.product.labels.LabelFieldKeys.SHOP_GSTIN;
import static com.inventory.product.labels.LabelFieldKeys.SHOP_NAME;
import static com.inventory.product.labels.LabelFieldKeys.SHOP_PHONE;
import static com.inventory.product.labels.LabelFieldKeys.SHOP_TAGLINE;
import static com.inventory.product.labels.LabelFieldKeys.SOLD_COUNT;
import static com.inventory.product.labels.LabelFieldKeys.STOCK_STATE;
import static com.inventory.product.labels.LabelFieldKeys.THRESHOLD_COUNT;

import static com.inventory.product.labels.Sensitivity.PUBLIC;
import static com.inventory.product.labels.Sensitivity.SHOP_INTERNAL;

import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.search.SearchSource;
import com.inventory.product.search.SearchSpec;
import com.inventory.product.search.SearchSpec.EnumValue;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Static definitions shared by the label layout feature: sticker size presets (Req 3.1, 3.5), the
 * Default_Layout and shop-type defaults (Req 3.6, 4.1, 4.2, 4.6), and the static catalog field sets
 * in catalog order (label Req 1.6, 1.7; card Req 1.2–1.4) — including the card-only fields that
 * the product card feature added to the shared Field_Catalog.
 *
 * <p>Everything here is immutable; collections are unmodifiable.
 */
public final class LabelLayoutDefaults {

  /** Preset id used when a layout omits {@code stickerSize}. */
  public static final String DEFAULT_STICKER_SIZE = "50x25";

  /** Print media used when a layout omits {@code printMedia} (Req 10.5). */
  public static final PrintMedia DEFAULT_PRINT_MEDIA = PrintMedia.ROLL;

  /** Sticker template used when a layout omits {@code template} (Req 11). */
  public static final StickerTemplate DEFAULT_TEMPLATE = StickerTemplate.STACKED;

  /** Barcode position used when a layout omits {@code barcodePosition} (Req 11). */
  public static final BarcodePosition DEFAULT_BARCODE_POSITION = BarcodePosition.TOP;

  /** Currency style used when a layout omits {@code currencyStyle} (Req 11). */
  public static final CurrencyStyle DEFAULT_CURRENCY_STYLE = CurrencyStyle.RUPEE_SYMBOL;

  /** Zone caps used for a sticker size with no explicit mapping. */
  private static final ZoneCaps DEFAULT_ZONE_CAPS = new ZoneCaps(1, 4, 2);

  /** Sticker size presets in display order. */
  public static final List<StickerSizeSpec> STICKER_SIZES =
      List.of(
          new StickerSizeSpec("50x25", 50, 25, 3),
          new StickerSizeSpec("38x25", 38, 25, 2),
          new StickerSizeSpec("100x50", 100, 50, 6));

  /**
   * Fixed sheet presets in display order (Req 10.2). Plain presets ({@code *_PLAIN}) derive their
   * grid from the chosen sticker size; die-cut presets carry fixed geometry and a compatible
   * sticker-size list.
   */
  public static final List<SheetPreset> SHEET_PRESETS =
      List.of(
          new SheetPreset(
              "A4_PLAIN", "A4 (plain)", 210, 297, 8, 8, 0, 0, 0, 0, List.of(), true),
          new SheetPreset(
              "LETTER_PLAIN", "Letter (plain)", 215.9, 279.4, 8, 8, 0, 0, 0, 0, List.of(), true),
          new SheetPreset(
              "A4_65UP", "A4 65-up (38×21mm)", 210, 297, 10.7, 4.75, 38.1, 21.2, 5, 13,
              List.of("38x25"), false),
          new SheetPreset(
              "A4_40UP", "A4 40-up (52×30mm)", 210, 297, 0, 0, 52.5, 29.7, 4, 10,
              List.of("50x25"), false),
          new SheetPreset(
              "A4_24UP", "A4 24-up (64×34mm)", 210, 297, 12.9, 9, 64, 33.9, 3, 8,
              List.of("50x25"), false),
          new SheetPreset(
              "A4_12UP", "A4 12-up (64×72mm)", 210, 297, 4.5, 9.75, 63.5, 72, 3, 4,
              List.of("100x50", "50x25"), false));

  private static final Set<ShopType> ALL_SHOP_TYPES =
      Set.of(ShopType.RETAILER, ShopType.DISTRIBUTOR, ShopType.WHOLESALER);

  private static final Set<ShopType> TRADE_SHOP_TYPES =
      Set.of(ShopType.DISTRIBUTOR, ShopType.WHOLESALER);

  private static final List<PrintableField> CORE_FIELDS =
      List.of(
          text(PRODUCT_NAME, "Product name", SourceGroup.PRODUCT, "name")
              .withSearch(SearchSpec.text(SearchSource.PRODUCT, "product.normalizedName", true, true)),
          text(COMPANY_NAME, "Company", SourceGroup.PRODUCT, "companyName")
              .withSearch(SearchSpec.text(SearchSource.PRODUCT, "product.companyName", true, true)),
          text(BARCODE_TEXT, "Barcode", SourceGroup.PRODUCT, "barcode")
              .withSearch(SearchSpec.text(SearchSource.PRODUCT, "product.barcode", true, false)),
          text(HSN, "HSN", SourceGroup.PRODUCT, "hsn")
              .withSearch(SearchSpec.text(SearchSource.PRODUCT, "product.hsn", true, false)),
          text(BASE_UNIT, "Unit", SourceGroup.PRODUCT, "baseUnit"),
          text(PACK_SIZE, "Pack size", SourceGroup.PRODUCT, "unitsPerPack"),
          text(DESCRIPTION, "Description", SourceGroup.PRODUCT, "description"));

  /** Card-only product fields, appended after {@link #CORE_FIELDS} in the catalog (card Req 1.3). */
  private static final List<PrintableField> CARD_PRODUCT_FIELDS =
      List.of(
          PrintableField.cardOnly(
              ITEM_TYPE, "Item type", SourceGroup.PRODUCT, ValueType.TEXT, "itemType", PUBLIC),
          PrintableField.cardOnly(
              DISCOUNT_APPLICABLE,
              "Discount applicable",
              SourceGroup.PRODUCT,
              ValueType.TEXT,
              "discountApplicable",
              PUBLIC));

  private static final List<PrintableField> PRICING_FIELDS =
      List.of(
          both(MRP, "MRP", SourceGroup.PRICING, ValueType.CURRENCY, ALL_SHOP_TYPES, "maximumRetailPrice"),
          both(
              SELLING_PRICE,
              "Selling price",
              SourceGroup.PRICING,
              ValueType.CURRENCY,
              ALL_SHOP_TYPES,
              "sellingPrice"),
          both(PTR, "PTR", SourceGroup.PRICING, ValueType.CURRENCY, TRADE_SHOP_TYPES, "priceToRetail"),
          both(COST_PRICE, "Cost price", SourceGroup.PRICING, ValueType.CURRENCY, TRADE_SHOP_TYPES, "costPrice")
              .withSensitivity(SHOP_INTERNAL),
          both(SALE_SCHEME, "Scheme", SourceGroup.PRICING, ValueType.TEXT, TRADE_SHOP_TYPES, "scheme"),
          both(GST_RATE, "GST %", SourceGroup.PRICING, ValueType.PERCENTAGE, TRADE_SHOP_TYPES, "sgst"));

  /**
   * Card-only pricing fields, appended after the static pricing fields and named rates in the
   * catalog (card Req 1.3, 1.4).
   */
  private static final List<PrintableField> CARD_PRICING_FIELDS =
      List.of(
          PrintableField.cardOnly(
              SALE_ADDITIONAL_DISCOUNT,
              "Additional discount",
              SourceGroup.PRICING,
              ValueType.PERCENTAGE,
              "saleAdditionalDiscount",
              PUBLIC),
          PrintableField.cardOnly(
              PURCHASE_ADDITIONAL_DISCOUNT,
              "Purchase add. discount",
              SourceGroup.PRICING,
              ValueType.PERCENTAGE,
              "purchaseAdditionalDiscount",
              SHOP_INTERNAL),
          PrintableField.cardOnly(
              PURCHASE_SCHEME,
              "Purchase scheme",
              SourceGroup.PRICING,
              ValueType.TEXT,
              "purchaseSchemeType",
              SHOP_INTERNAL),
          PrintableField.cardOnly(
              EFFECTIVE_COST_PRICE,
              "Effective cost",
              SourceGroup.PRICING,
              ValueType.CURRENCY,
              "effectiveCostPrice",
              SHOP_INTERNAL));

  private static final List<PrintableField> LOT_FIELDS =
      List.of(
          text(BATCH_NO, "Batch no.", SourceGroup.LOT, "batchNo")
              .withSearch(SearchSpec.text(SearchSource.LOT, "batchNo", false, false)),
          both(EXPIRY_DATE, "Expiry", SourceGroup.LOT, ValueType.DATE, ALL_SHOP_TYPES, "expiryDate")
              .withSearch(SearchSpec.date(SearchSource.LOT, "expiryDate", true)),
          both(RECEIVED_DATE, "Received on", SourceGroup.LOT, ValueType.DATE, ALL_SHOP_TYPES, "createdAt")
              .withSearch(SearchSpec.date(SearchSource.LOT, "createdAt", true)));

  /** Card-only lot fields, appended after {@link #LOT_FIELDS} in the catalog (card Req 1.3). */
  private static final List<PrintableField> CARD_LOT_FIELDS =
      List.of(
          PrintableField.cardOnly(LOCATION, "Location", SourceGroup.LOT, ValueType.TEXT, "location", PUBLIC)
              .withSearch(SearchSpec.text(SearchSource.LOT, "location", true, false)),
          PrintableField.cardOnly(
              AVAILABLE_COUNT, "Available", SourceGroup.LOT, ValueType.NUMBER, "availableCount", PUBLIC),
          PrintableField.cardOnly(
              CURRENT_COUNT, "Current stock", SourceGroup.LOT, ValueType.NUMBER, "currentCount", PUBLIC)
              .withSearch(SearchSpec.number(SearchSource.LOT, "currentCount", true)),
          PrintableField.cardOnly(
              RECEIVED_COUNT, "Received", SourceGroup.LOT, ValueType.NUMBER, "receivedCount", PUBLIC),
          PrintableField.cardOnly(SOLD_COUNT, "Sold", SourceGroup.LOT, ValueType.NUMBER, "soldCount", PUBLIC),
          PrintableField.cardOnly(
              THRESHOLD_COUNT,
              "Low-stock threshold",
              SourceGroup.LOT,
              ValueType.NUMBER,
              "thresholdCount",
              PUBLIC),
          PrintableField.cardOnly(
              PURCHASE_DATE, "Purchased on", SourceGroup.LOT, ValueType.DATE, "purchaseDate", PUBLIC)
              .withSearch(SearchSpec.date(SearchSource.LOT, "purchaseDate", true)),
          PrintableField.cardOnly(
                  BILLING_MODE, "Billing mode", SourceGroup.LOT, ValueType.TEXT, "billingMode", PUBLIC)
              .withSearch(
                  SearchSpec.enumeration(
                      SearchSource.LOT,
                      "billingMode",
                      false,
                      List.of(new EnumValue("REGULAR", "Regular"), new EnumValue("BASIC", "Basic")))));

  /**
   * Search-only fields computed in the pipeline (advanced-product-search R1.2). Not on cards, not
   * on stickers; they exist so the filter panel can offer them.
   */
  private static final List<PrintableField> SEARCH_LOT_FIELDS =
      List.of(
          new PrintableField(
              STOCK_STATE,
              "Stock",
              SourceGroup.LOT,
              ValueType.TEXT,
              ALL_SHOP_TYPES,
              null,
              Set.of(FieldUsage.SEARCH),
              PUBLIC,
              null,
              SearchSpec.enumeration(
                  SearchSource.COMPUTED,
                  "stockState",
                  false,
                  List.of(
                      new EnumValue("IN_STOCK", "In stock"),
                      new EnumValue("LOW_STOCK", "Low stock"),
                      new EnumValue("SOLD_OUT", "Sold out")))));

  /** Shop identity fields are sticker-only: a card already sits inside the shop's own UI. */
  private static final List<PrintableField> SHOP_FIELDS =
      List.of(
          shopText(SHOP_NAME, "Shop name"),
          shopText(SHOP_TAGLINE, "Tagline"),
          shopText(SHOP_PHONE, "Phone"),
          shopText(SHOP_EMAIL, "Email"),
          shopText(SHOP_ADDRESS, "Address"),
          shopText(SHOP_GSTIN, "GSTIN"),
          shopText(SHOP_FSSAI, "FSSAI"),
          shopText(SHOP_DL_NO, "D.L. No."));

  private static final LabelLayoutConfig DEFAULT_LAYOUT =
      new LabelLayoutConfig(
          List.of(PRODUCT_NAME, COMPANY_NAME),
          DEFAULT_STICKER_SIZE,
          true,
          false,
          BlankValueBehavior.HIDE_LINE);

  private static final LabelLayoutConfig RETAILER_DEFAULTS =
      new LabelLayoutConfig(
          List.of(PRODUCT_NAME, COMPANY_NAME, MRP),
          DEFAULT_STICKER_SIZE,
          true,
          false,
          BlankValueBehavior.HIDE_LINE);

  private static final LabelLayoutConfig TRADE_DEFAULTS =
      new LabelLayoutConfig(
          List.of(PRODUCT_NAME, COMPANY_NAME, PTR, MRP),
          DEFAULT_STICKER_SIZE,
          true,
          false,
          BlankValueBehavior.HIDE_LINE);

  private LabelLayoutDefaults() {}

  // ---- sticker sizes -------------------------------------------------------------------------

  /**
   * Per-zone field caps for the {@link StickerTemplate#COMPACT} template, by sticker size (Req 11):
   * {@code 50x25 → 1/4/2}, {@code 38x25 → 1/3/1}, {@code 100x50 → 1/6/3}. Unknown sizes fall back to
   * the {@link #DEFAULT_STICKER_SIZE} caps.
   */
  public static ZoneCaps zoneCaps(String size) {
    if (size == null) {
      return DEFAULT_ZONE_CAPS;
    }
    return switch (size.trim()) {
      case "38x25" -> new ZoneCaps(1, 3, 1);
      case "100x50" -> new ZoneCaps(1, 6, 3);
      case "50x25" -> new ZoneCaps(1, 4, 2);
      default -> DEFAULT_ZONE_CAPS;
    };
  }

  /** Looks up a sticker size preset by id. */
  public static Optional<StickerSizeSpec> stickerSize(String id) {
    if (id == null) {
      return Optional.empty();
    }
    return STICKER_SIZES.stream().filter(s -> id.equals(s.size())).findFirst();
  }

  /** The preset matching {@link #DEFAULT_STICKER_SIZE}. */
  public static StickerSizeSpec defaultStickerSize() {
    return STICKER_SIZES.get(0);
  }

  // ---- sheet presets -------------------------------------------------------------------------

  /** Looks up a sheet preset by id (Req 10.4). */
  public static Optional<SheetPreset> sheetPreset(String id) {
    if (id == null) {
      return Optional.empty();
    }
    return SHEET_PRESETS.stream().filter(p -> id.equals(p.id())).findFirst();
  }

  // ---- layouts -------------------------------------------------------------------------------

  /**
   * The Default_Layout applied when a shop has never saved a layout: {@code productName,
   * companyName}; {@code 50x25}; barcode text shown; field labels hidden; blank lines hidden (Req
   * 3.6).
   */
  public static LabelLayoutConfig defaultLayout() {
    return DEFAULT_LAYOUT;
  }

  /**
   * Suggested starting layout for a shop type (Req 4.1, 4.2, 4.6). {@code null} or unknown types
   * are treated as {@link ShopType#RETAILER}.
   */
  public static LabelLayoutConfig shopTypeDefaults(ShopType shopType) {
    return switch (effectiveShopType(shopType)) {
      case DISTRIBUTOR, WHOLESALER -> TRADE_DEFAULTS;
      case RETAILER -> RETAILER_DEFAULTS;
    };
  }

  /** Normalizes a possibly-null shop type: {@code null} becomes {@link ShopType#RETAILER}. */
  public static ShopType effectiveShopType(ShopType shopType) {
    return shopType == null ? ShopType.RETAILER : shopType;
  }

  // ---- static field sets (catalog order) -----------------------------------------------------

  /** Product group sticker-and-card fields in catalog order. */
  public static List<PrintableField> coreFields() {
    return CORE_FIELDS;
  }

  /** Card-only product fields; the catalog appends them after {@link #coreFields()}. */
  public static List<PrintableField> cardProductFields() {
    return CARD_PRODUCT_FIELDS;
  }

  /** Static pricing group fields in catalog order (named rates are appended by the catalog). */
  public static List<PrintableField> pricingFields() {
    return PRICING_FIELDS;
  }

  /** Card-only pricing fields; the catalog appends them after the named rates. */
  public static List<PrintableField> cardPricingFields() {
    return CARD_PRICING_FIELDS;
  }

  /** Lot group sticker-and-card fields in catalog order. */
  public static List<PrintableField> lotFields() {
    return LOT_FIELDS;
  }

  /** Card-only lot fields; the catalog appends them after {@link #lotFields()}. */
  public static List<PrintableField> cardLotFields() {
    return CARD_LOT_FIELDS;
  }

  /** Search-only computed lot fields; the catalog appends them after {@link #cardLotFields()}. */
  public static List<PrintableField> searchLotFields() {
    return SEARCH_LOT_FIELDS;
  }

  /** Shop group fields in catalog order (sticker only). */
  public static List<PrintableField> shopFields() {
    return SHOP_FIELDS;
  }

  /**
   * Builds the catalog field for a named pricing rate: key {@code pricing.rate.<name>}, label
   * {@code "Rate: <name>"}, currency, DISTRIBUTOR/WHOLESALER only on stickers. On cards the value is
   * picked from the {@code rates} list by name.
   */
  public static PrintableField namedRateField(String rateName) {
    return both(
        LabelFieldKeys.pricingRateKey(rateName),
        "Rate: " + rateName,
        SourceGroup.PRICING,
        ValueType.CURRENCY,
        TRADE_SHOP_TYPES,
        "rates");
  }

  private static PrintableField text(
      String fieldKey, String label, SourceGroup group, String itemPath) {
    return both(fieldKey, label, group, ValueType.TEXT, ALL_SHOP_TYPES, itemPath);
  }

  private static PrintableField shopText(String fieldKey, String label) {
    return PrintableField.labelOnly(fieldKey, label, SourceGroup.SHOP, ValueType.TEXT, ALL_SHOP_TYPES);
  }

  /** A field usable on both stickers and cards. */
  private static PrintableField both(
      String fieldKey,
      String label,
      SourceGroup group,
      ValueType valueType,
      Set<ShopType> shopTypes,
      String itemPath) {
    return new PrintableField(
        fieldKey,
        label,
        group,
        valueType,
        shopTypes,
        null,
        PrintableField.DEFAULT_USAGES,
        PUBLIC,
        itemPath);
  }
}
