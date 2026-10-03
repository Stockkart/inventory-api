package com.inventory.product.labels;

import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.product.domain.model.enums.ShopType;
import java.util.List;
import java.util.Optional;

/**
 * The Field_Catalog for one shop: every printable field in catalog order, deduplicated by {@code
 * fieldKey}, plus the sticker size presets and the context the resolver needs (Req 1.1, 1.2).
 *
 * @param fields ordered, deduplicated printable fields
 * @param stickerSizes the sticker size presets (always included, Req 3.5)
 * @param effectiveShopType the shop's type; null/unknown is normalized to {@code RETAILER}
 * @param verticalSchemaLoaded false when the vertical schema could not be loaded (Req 1.9)
 * @param inventorySchemaFields {@code inventory}-entity schema fields, for the resolver
 * @param productSchemaFields {@code product}-entity schema fields, for the resolver
 */
public record FieldCatalog(
    List<PrintableField> fields,
    List<StickerSizeSpec> stickerSizes,
    ShopType effectiveShopType,
    boolean verticalSchemaLoaded,
    List<VerticalSchemaField> inventorySchemaFields,
    List<VerticalSchemaField> productSchemaFields) {

  public FieldCatalog {
    fields = fields == null ? List.of() : List.copyOf(fields);
    stickerSizes = stickerSizes == null ? List.of() : List.copyOf(stickerSizes);
    effectiveShopType = effectiveShopType == null ? ShopType.RETAILER : effectiveShopType;
    inventorySchemaFields =
        inventorySchemaFields == null ? List.of() : List.copyOf(inventorySchemaFields);
    productSchemaFields = productSchemaFields == null ? List.of() : List.copyOf(productSchemaFields);
  }

  /** Looks up a field by its key. */
  public Optional<PrintableField> find(String fieldKey) {
    if (fieldKey == null) {
      return Optional.empty();
    }
    return fields.stream().filter(f -> fieldKey.equals(f.fieldKey())).findFirst();
  }

  /** Looks up a sticker size preset by its id. */
  public Optional<StickerSizeSpec> findStickerSize(String size) {
    if (size == null) {
      return Optional.empty();
    }
    return stickerSizes.stream().filter(s -> size.equals(s.size())).findFirst();
  }
}
