package com.inventory.product.labels;

import com.inventory.product.domain.model.enums.ShopType;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * One field of the Field_Catalog: a value that can be printed on a barcode sticker, shown on a
 * product card, or both (label layout Req 1.2; card layout Req 1.1).
 *
 * <p>The record keeps its historical name because the {@code fieldKey}s it carries are persisted in
 * shop documents and exchanged with the frontend; renaming the type is churn with no behavioural
 * value. Read it as "catalog field".
 *
 * @param fieldKey stable key, e.g. {@code productName}, {@code pricing.rate.Retail}, {@code
 *     vertical.batchNo}
 * @param label human-readable label shown in configuration screens, on stickers and on cards (1–60
 *     chars)
 * @param sourceGroup where the value is resolved from
 * @param valueType how the value is formatted
 * @param availableForShopTypes shop types allowed to print this field on a sticker (1–3 entries).
 *     Cards are staff-facing and ignore this constraint.
 * @param schemaApiKey for {@link SourceGroup#VERTICAL} fields, the schema {@code apiKey} used to
 *     look the value up in the merged vertical map; {@code null} for every other group
 * @param usages the surfaces this field may be used on; never empty
 * @param sensitivity whether the value is safe to show over the counter
 * @param itemPath dot path into the inventory summary DTO the frontend reads a card value from
 *     (e.g. {@code maximumRetailPrice}, {@code verticalFields.brand}); required when {@code usages}
 *     contains {@link FieldUsage#CARD}, {@code null} otherwise
 */
public record PrintableField(
    String fieldKey,
    String label,
    SourceGroup sourceGroup,
    ValueType valueType,
    Set<ShopType> availableForShopTypes,
    String schemaApiKey,
    Set<FieldUsage> usages,
    Sensitivity sensitivity,
    String itemPath) {

  /** Usages applied when a caller does not say otherwise. */
  public static final Set<FieldUsage> DEFAULT_USAGES = Set.of(FieldUsage.LABEL, FieldUsage.CARD);

  public PrintableField {
    availableForShopTypes =
        availableForShopTypes == null ? Set.of() : Set.copyOf(availableForShopTypes);
    usages = usages == null || usages.isEmpty() ? DEFAULT_USAGES : Set.copyOf(EnumSet.copyOf(usages));
    sensitivity = sensitivity == null ? Sensitivity.PUBLIC : sensitivity;
    itemPath = StringUtils.hasText(itemPath) ? itemPath.trim() : null;
    if (usages.contains(FieldUsage.CARD) && itemPath == null) {
      throw new IllegalArgumentException(
          "Field " + fieldKey + " is usable on cards and therefore needs an itemPath");
    }
  }

  /**
   * Legacy constructor for non-vertical sticker fields. Usable on both labels and cards; the card
   * value is read from the DTO property named exactly like the {@code fieldKey}.
   */
  public PrintableField(
      String fieldKey,
      String label,
      SourceGroup sourceGroup,
      ValueType valueType,
      Set<ShopType> availableForShopTypes) {
    this(fieldKey, label, sourceGroup, valueType, availableForShopTypes, null);
  }

  /**
   * Legacy constructor for fields with a schema api key. Usable on both labels and cards; the card
   * value is read from the DTO property named exactly like the {@code fieldKey}.
   */
  public PrintableField(
      String fieldKey,
      String label,
      SourceGroup sourceGroup,
      ValueType valueType,
      Set<ShopType> availableForShopTypes,
      String schemaApiKey) {
    this(
        fieldKey,
        label,
        sourceGroup,
        valueType,
        availableForShopTypes,
        schemaApiKey,
        DEFAULT_USAGES,
        Sensitivity.PUBLIC,
        fieldKey);
  }

  /** A field usable on stickers only (no card value, no item path). */
  public static PrintableField labelOnly(
      String fieldKey,
      String label,
      SourceGroup sourceGroup,
      ValueType valueType,
      Set<ShopType> availableForShopTypes) {
    return new PrintableField(
        fieldKey,
        label,
        sourceGroup,
        valueType,
        availableForShopTypes,
        null,
        Set.of(FieldUsage.LABEL),
        Sensitivity.PUBLIC,
        null);
  }

  /** A field usable on cards only. */
  public static PrintableField cardOnly(
      String fieldKey,
      String label,
      SourceGroup sourceGroup,
      ValueType valueType,
      String itemPath,
      Sensitivity sensitivity) {
    return new PrintableField(
        fieldKey,
        label,
        sourceGroup,
        valueType,
        ALL_SHOP_TYPES,
        null,
        Set.of(FieldUsage.CARD),
        sensitivity,
        itemPath);
  }

  /** Copy of this field with a different item path. */
  public PrintableField withItemPath(String newItemPath) {
    return new PrintableField(
        fieldKey,
        label,
        sourceGroup,
        valueType,
        availableForShopTypes,
        schemaApiKey,
        usages,
        sensitivity,
        newItemPath);
  }

  /** Copy of this field with a different sensitivity. */
  public PrintableField withSensitivity(Sensitivity newSensitivity) {
    return new PrintableField(
        fieldKey,
        label,
        sourceGroup,
        valueType,
        availableForShopTypes,
        schemaApiKey,
        usages,
        newSensitivity,
        itemPath);
  }

  /** Copy of this field with different usages (and, for card use, item path). */
  public PrintableField withUsages(Set<FieldUsage> newUsages, String newItemPath) {
    return new PrintableField(
        fieldKey,
        label,
        sourceGroup,
        valueType,
        availableForShopTypes,
        schemaApiKey,
        newUsages,
        sensitivity,
        newItemPath);
  }

  /** True when a shop of the given type may print this field on a sticker. */
  public boolean isAvailableFor(ShopType shopType) {
    return shopType != null && availableForShopTypes.contains(shopType);
  }

  /** True when this field may be used on the given surface. */
  public boolean usableFor(FieldUsage usage) {
    return usage != null && usages.contains(usage);
  }

  private static final Set<ShopType> ALL_SHOP_TYPES =
      Set.of(ShopType.RETAILER, ShopType.DISTRIBUTOR, ShopType.WHOLESALER);
}
