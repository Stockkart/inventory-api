package com.inventory.product.labels;

import com.inventory.pluginengine.InventoryExtensionRepository;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pluginengine.schema.VerticalSchemaFieldResolver;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Batch read of vertical extension rows for the lots selected by a labels request (Req 6.6).
 *
 * <p>Resolves the shop's plugin through {@link PluginRegistry} exactly like {@code
 * InventoryVerticalExtensionHandler} does, then calls {@link
 * InventoryExtensionRepository#findByInventoryIds(String, List)} once for all requested lot ids.
 * Any missing piece (no {@code verticalId}, no plugin, no extension repository) or a failing read
 * degrades to an empty map with a single WARN so label printing never fails because of vertical
 * data.
 */
@Component
@Slf4j
public class LabelExtensionReader {

  private final PluginRegistry pluginRegistry;

  public LabelExtensionReader(PluginRegistry pluginRegistry) {
    this.pluginRegistry = pluginRegistry;
  }

  /**
   * Reads the extension rows for the given lots in one repository call.
   *
   * @param shop the active shop (already loaded by the caller)
   * @param inventoryIds ids of the selected lots; nulls/blanks and duplicates are ignored
   * @return inventoryId → flat map of stored extension values ({@code apiKey → raw value}); never
   *     null, empty when nothing can be read
   */
  public Map<String, Map<String, Object>> readByInventoryIds(
      Shop shop, Collection<String> inventoryIds) {
    List<String> ids = distinctIds(inventoryIds);
    if (shop == null || ids.isEmpty()) {
      return Map.of();
    }
    String shopId = shop.getShopId();
    String verticalId = shop.getVerticalId();
    if (!StringUtils.hasText(verticalId)) {
      log.warn(
          "Shop {} has no verticalId — vertical label values skipped for {} lot(s)",
          shopId,
          ids.size());
      return Map.of();
    }
    try {
      InventoryExtensionRepository repository =
          pluginRegistry
              .find(verticalId)
              .flatMap(p -> p.getInventoryExtensionRepository())
              .orElse(null);
      if (repository == null) {
        log.warn(
            "No inventory extension repository for vertical {} (shop {}) — vertical label values"
                + " skipped for {} lot(s)",
            verticalId,
            shopId,
            ids.size());
        return Map.of();
      }
      Map<String, Map<String, Object>> rows = repository.findByInventoryIds(shopId, ids);
      return rows == null ? Map.of() : rows;
    } catch (RuntimeException e) {
      log.warn(
          "Could not read vertical extension rows for shop {} (vertical {}) — vertical label"
              + " values skipped for {} lot(s): {}",
          shopId,
          verticalId,
          ids.size(),
          e.getMessage());
      return Map.of();
    }
  }

  /**
   * Merges a lot's core properties with its stored extension row into one flat map keyed by the
   * schema field's {@code apiKey} (falling back to {@code key}), matching {@link
   * PrintableField#schemaApiKey()} so the resolver can look vertical values up directly.
   *
   * <p>Delegates to {@link VerticalSchemaFieldResolver#mergeVerticalFields(List, Object, Object,
   * Map)} with the lot as the fallback bean: core-storage fields are read from the lot bean, {@code
   * storage: extension} fields come from {@code extensionRow}. Absent values are simply missing
   * from the result.
   *
   * @param lot the selected lot; may be null (then only extension values are returned)
   * @param extensionRow the lot's extension row from {@link #readByInventoryIds}; may be null
   * @param catalog the shop's catalog, source of {@link FieldCatalog#inventorySchemaFields()}
   * @return apiKey → raw value; never null
   */
  public Map<String, Object> mergeVerticalFields(
      Inventory lot, Map<String, Object> extensionRow, FieldCatalog catalog) {
    if (catalog == null || catalog.inventorySchemaFields().isEmpty()) {
      return Map.of();
    }
    List<VerticalSchemaField> schemaFields = catalog.inventorySchemaFields();
    Map<String, Object> byKey =
        VerticalSchemaFieldResolver.mergeVerticalFields(schemaFields, null, lot, extensionRow);
    if (byKey.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> byApiKey = new LinkedHashMap<>();
    for (VerticalSchemaField field : schemaFields) {
      String key = field.getKey();
      if (!StringUtils.hasText(key) || !byKey.containsKey(key)) {
        continue;
      }
      String apiKey = StringUtils.hasText(field.getApiKey()) ? field.getApiKey() : key;
      byApiKey.putIfAbsent(apiKey, byKey.get(key));
    }
    return byApiKey;
  }

  private static List<String> distinctIds(Collection<String> inventoryIds) {
    if (inventoryIds == null || inventoryIds.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> distinct = new LinkedHashSet<>();
    for (String id : inventoryIds) {
      if (StringUtils.hasText(id)) {
        distinct.add(id);
      }
    }
    return List.copyOf(distinct);
  }
}
