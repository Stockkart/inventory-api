package com.inventory.product.labels;

import com.inventory.product.domain.model.enums.ShopType;
import java.util.Set;

/**
 * One field that can be printed on a barcode sticker (Req 1.2).
 *
 * @param fieldKey stable key, e.g. {@code productName}, {@code pricing.rate.Retail}, {@code
 *     vertical.batchNo}
 * @param label human-readable label shown in the configuration screen and on stickers (1–60 chars)
 * @param sourceGroup where the value is resolved from
 * @param valueType how the value is formatted
 * @param availableForShopTypes shop types allowed to enable this field (1–3 entries)
 * @param schemaApiKey for {@link SourceGroup#VERTICAL} fields, the schema {@code apiKey} used to
 *     look the value up in the merged vertical map; {@code null} for every other group
 */
public record PrintableField(
    String fieldKey,
    String label,
    SourceGroup sourceGroup,
    ValueType valueType,
    Set<ShopType> availableForShopTypes,
    String schemaApiKey) {

  public PrintableField {
    availableForShopTypes =
        availableForShopTypes == null ? Set.of() : Set.copyOf(availableForShopTypes);
  }

  /** Convenience constructor for non-vertical fields (no schema api key). */
  public PrintableField(
      String fieldKey,
      String label,
      SourceGroup sourceGroup,
      ValueType valueType,
      Set<ShopType> availableForShopTypes) {
    this(fieldKey, label, sourceGroup, valueType, availableForShopTypes, null);
  }

  /** True when a shop of the given type may enable this field. */
  public boolean isAvailableFor(ShopType shopType) {
    return shopType != null && availableForShopTypes.contains(shopType);
  }
}
