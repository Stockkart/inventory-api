package com.inventory.product.labels;

import static com.inventory.product.labels.LabelFieldKeys.BARCODE_TEXT;
import static com.inventory.product.labels.LabelFieldKeys.BASE_UNIT;
import static com.inventory.product.labels.LabelFieldKeys.BATCH_NO;
import static com.inventory.product.labels.LabelFieldKeys.COMPANY_NAME;
import static com.inventory.product.labels.LabelFieldKeys.COST_PRICE;
import static com.inventory.product.labels.LabelFieldKeys.DESCRIPTION;
import static com.inventory.product.labels.LabelFieldKeys.EXPIRY_DATE;
import static com.inventory.product.labels.LabelFieldKeys.GST_RATE;
import static com.inventory.product.labels.LabelFieldKeys.HSN;
import static com.inventory.product.labels.LabelFieldKeys.MRP;
import static com.inventory.product.labels.LabelFieldKeys.PACK_SIZE;
import static com.inventory.product.labels.LabelFieldKeys.PRODUCT_NAME;
import static com.inventory.product.labels.LabelFieldKeys.PTR;
import static com.inventory.product.labels.LabelFieldKeys.RECEIVED_DATE;
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

import com.inventory.product.domain.model.enums.ShopType;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Static definitions shared by the label layout feature: sticker size presets (Req 3.1, 3.5), the
 * Default_Layout and shop-type defaults (Req 3.6, 4.1, 4.2, 4.6), and the four static printable
 * field sets in catalog order (Req 1.6, 1.7).
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
          text(PRODUCT_NAME, "Product name", SourceGroup.PRODUCT),
          text(COMPANY_NAME, "Company", SourceGroup.PRODUCT),
          text(BARCODE_TEXT, "Barcode", SourceGroup.PRODUCT),
          text(HSN, "HSN", SourceGroup.PRODUCT),
          text(BASE_UNIT, "Unit", SourceGroup.PRODUCT),
          text(PACK_SIZE, "Pack size", SourceGroup.PRODUCT),
          text(DESCRIPTION, "Description", SourceGroup.PRODUCT));

  private static final List<PrintableField> PRICING_FIELDS =
      List.of(
          new PrintableField(MRP, "MRP", SourceGroup.PRICING, ValueType.CURRENCY, ALL_SHOP_TYPES),
          new PrintableField(
              SELLING_PRICE,
              "Selling price",
              SourceGroup.PRICING,
              ValueType.CURRENCY,
              ALL_SHOP_TYPES),
          new PrintableField(
              PTR, "PTR", SourceGroup.PRICING, ValueType.CURRENCY, TRADE_SHOP_TYPES),
          new PrintableField(
              COST_PRICE, "Cost price", SourceGroup.PRICING, ValueType.CURRENCY, TRADE_SHOP_TYPES),
          new PrintableField(
              SALE_SCHEME, "Scheme", SourceGroup.PRICING, ValueType.TEXT, TRADE_SHOP_TYPES),
          new PrintableField(
              GST_RATE, "GST %", SourceGroup.PRICING, ValueType.PERCENTAGE, TRADE_SHOP_TYPES));

  private static final List<PrintableField> LOT_FIELDS =
      List.of(
          text(BATCH_NO, "Batch no.", SourceGroup.LOT),
          new PrintableField(
              EXPIRY_DATE, "Expiry", SourceGroup.LOT, ValueType.DATE, ALL_SHOP_TYPES),
          new PrintableField(
              RECEIVED_DATE, "Received on", SourceGroup.LOT, ValueType.DATE, ALL_SHOP_TYPES));

  private static final List<PrintableField> SHOP_FIELDS =
      List.of(
          text(SHOP_NAME, "Shop name", SourceGroup.SHOP),
          text(SHOP_TAGLINE, "Tagline", SourceGroup.SHOP),
          text(SHOP_PHONE, "Phone", SourceGroup.SHOP),
          text(SHOP_EMAIL, "Email", SourceGroup.SHOP),
          text(SHOP_ADDRESS, "Address", SourceGroup.SHOP),
          text(SHOP_GSTIN, "GSTIN", SourceGroup.SHOP),
          text(SHOP_FSSAI, "FSSAI", SourceGroup.SHOP),
          text(SHOP_DL_NO, "D.L. No.", SourceGroup.SHOP));

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

  /** Product group fields in catalog order. */
  public static List<PrintableField> coreFields() {
    return CORE_FIELDS;
  }

  /** Static pricing group fields in catalog order (named rates are appended by the catalog). */
  public static List<PrintableField> pricingFields() {
    return PRICING_FIELDS;
  }

  /** Lot group fields in catalog order. */
  public static List<PrintableField> lotFields() {
    return LOT_FIELDS;
  }

  /** Shop group fields in catalog order. */
  public static List<PrintableField> shopFields() {
    return SHOP_FIELDS;
  }

  /**
   * Builds the printable field for a named pricing rate: key {@code pricing.rate.<name>}, label
   * {@code "Rate: <name>"}, currency, DISTRIBUTOR/WHOLESALER only.
   */
  public static PrintableField namedRateField(String rateName) {
    return new PrintableField(
        LabelFieldKeys.pricingRateKey(rateName),
        "Rate: " + rateName,
        SourceGroup.PRICING,
        ValueType.CURRENCY,
        TRADE_SHOP_TYPES);
  }

  private static PrintableField text(String fieldKey, String label, SourceGroup group) {
    return new PrintableField(fieldKey, label, group, ValueType.TEXT, ALL_SHOP_TYPES);
  }
}
