package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.labels.PrintableField;
import com.inventory.product.labels.SourceGroup;
import com.inventory.product.labels.ValueType;
import java.util.Comparator;
import java.util.List;

/**
 * One printable field in the field catalog.
 *
 * <p>{@code sourceGroup} and {@code valueType} serialise to their lowercase wire names ({@code
 * "product"}, {@code "currency"}, ...) via {@code @JsonValue} on the enums. {@code
 * availableForShopTypes} is emitted in a stable (enum declaration) order so the catalog is
 * deterministic.
 */
public record PrintableFieldDto(
    String fieldKey,
    String label,
    SourceGroup sourceGroup,
    ValueType valueType,
    List<ShopType> availableForShopTypes,
    String schemaApiKey) {

  public PrintableFieldDto {
    availableForShopTypes =
        availableForShopTypes == null ? List.of() : List.copyOf(availableForShopTypes);
  }

  public static PrintableFieldDto from(PrintableField field) {
    List<ShopType> shopTypes =
        field.availableForShopTypes().stream().sorted(Comparator.naturalOrder()).toList();
    return new PrintableFieldDto(
        field.fieldKey(),
        field.label(),
        field.sourceGroup(),
        field.valueType(),
        shopTypes,
        field.schemaApiKey());
  }
}
