package com.inventory.product.cardlayout;

import static com.inventory.product.labels.LabelFieldKeys.AVAILABLE_COUNT;
import static com.inventory.product.labels.LabelFieldKeys.BARCODE_TEXT;
import static com.inventory.product.labels.LabelFieldKeys.BATCH_NO;
import static com.inventory.product.labels.LabelFieldKeys.COMPANY_NAME;
import static com.inventory.product.labels.LabelFieldKeys.DESCRIPTION;
import static com.inventory.product.labels.LabelFieldKeys.EXPIRY_DATE;
import static com.inventory.product.labels.LabelFieldKeys.LOCATION;
import static com.inventory.product.labels.LabelFieldKeys.MRP;
import static com.inventory.product.labels.LabelFieldKeys.PURCHASE_DATE;
import static com.inventory.product.labels.LabelFieldKeys.RECEIVED_COUNT;
import static com.inventory.product.labels.LabelFieldKeys.SALE_ADDITIONAL_DISCOUNT;
import static com.inventory.product.labels.LabelFieldKeys.SELLING_PRICE;
import static com.inventory.product.labels.LabelFieldKeys.SOLD_COUNT;

import com.inventory.pluginengine.cards.CardBlankValueBehavior;
import com.inventory.pluginengine.cards.CardField;
import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardOptions;
import com.inventory.pluginengine.cards.CardRow;
import com.inventory.pluginengine.cards.CardSection;
import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.pluginengine.cards.CardVariant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in card layouts and limits (configurable-product-card Req 3.3, 6.1, 6.2).
 *
 * <p>The defaults reproduce today's hardcoded cards line for line so a shop that never opens the
 * settings sees no change. Labels are overridden only where the catalog label differs from what
 * the card prints today.
 */
public final class CardLayoutDefaults {

  // ---- caps (Req 3.3) ------------------------------------------------------------------------
  public static final int MAX_SECTIONS = 6;
  public static final int MAX_ROWS_PER_SECTION = 8;
  public static final int MAX_FIELDS_PER_ROW = 3;
  public static final int MAX_FIELDS_TOTAL = 20;
  public static final int MAX_TEXT_LENGTH = 40;

  /** Options applied when a save omits them (Req 3.6). */
  public static final CardOptions DEFAULT_OPTIONS = CardOptions.DEFAULT;

  // ---- section ids used by the defaults ------------------------------------------------------
  static final String IDENTITY = "identity";
  static final String STOCK = "stock";
  static final String PRICING = "pricing";
  static final String DATES = "dates";

  /** Req 6.1 — today's {@code ProductSearchCard} body. */
  private static final CardLayout PRODUCT_SEARCH =
      CardLayout.of(
          DEFAULT_OPTIONS,
          CardSection.of(
              IDENTITY,
              CardRow.single(COMPANY_NAME),
              CardRow.of(CardField.labelled(BATCH_NO, "Batch")),
              CardRow.of(CardField.labelled(BARCODE_TEXT, "Barcode")),
              CardRow.single(LOCATION)),
          CardSection.divided(
              STOCK,
              CardRow.single(AVAILABLE_COUNT),
              CardRow.of(CardField.of(RECEIVED_COUNT), CardField.of(SOLD_COUNT))),
          CardSection.of(
              PRICING,
              CardRow.of(CardField.strong(SELLING_PRICE, "Selling Price")),
              CardRow.of(CardField.strong(MRP)),
              CardRow.of(CardField.strong(SALE_ADDITIONAL_DISCOUNT, "Additional Discount"))),
          CardSection.of(
              DATES,
              CardRow.of(CardField.labelled(EXPIRY_DATE, "Expires")),
              CardRow.of(CardField.labelled(PURCHASE_DATE, "Purchased"))));

  /** Req 6.2 — today's Scan &amp; Sell {@code SearchDropdownItem} lines. */
  private static final CardLayout SCAN_SELL =
      CardLayout.of(
          new CardOptions(CardBlankValueBehavior.HIDE_LINE, false, false),
          CardSection.of(
              IDENTITY,
              CardRow.single(COMPANY_NAME),
              CardRow.of(CardField.labelled(BATCH_NO, "Batch")),
              CardRow.of(CardField.labelled(BARCODE_TEXT, "Barcode")),
              CardRow.single(AVAILABLE_COUNT),
              CardRow.of(CardField.strong(MRP)),
              CardRow.of(CardField.strong(SELLING_PRICE, "Selling")),
              CardRow.of(CardField.strong(EXPIRY_DATE, "Expires"))));

  /** Core surface definitions in display order (Req 2.1). */
  static final List<CardSurfaceDefinition> CORE_SURFACES =
      List.of(
          new CardSurfaceDefinition(
              CardSurfaceIds.PRODUCT_SEARCH,
              "Product search",
              true,
              Set.of(),
              bothVariants(PRODUCT_SEARCH)),
          new CardSurfaceDefinition(
              CardSurfaceIds.SCAN_SELL,
              "Scan & Sell results",
              true,
              Set.of(DESCRIPTION),
              bothVariants(SCAN_SELL)));

  private CardLayoutDefaults() {}

  /** The built-in product-search layout. */
  public static CardLayout productSearch() {
    return PRODUCT_SEARCH;
  }

  /** The built-in Scan &amp; Sell layout. */
  public static CardLayout scanSell() {
    return SCAN_SELL;
  }

  /** The core surfaces, in display order. */
  public static List<CardSurfaceDefinition> coreSurfaces() {
    return CORE_SURFACES;
  }

  private static Map<CardVariant, CardLayout> bothVariants(CardLayout layout) {
    return Map.of(CardVariant.REGULAR, layout, CardVariant.BASIC, layout);
  }
}
