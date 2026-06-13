package com.inventory.product.service.vertical;

import com.inventory.pluginengine.InventoryExtensionRepository;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pluginengine.schema.VerticalSchemaFieldResolver;
import com.inventory.pluginengine.schema.VerticalSchemaStorage;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.CreateInventoryRequest;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import com.inventory.product.rest.dto.response.InventoryDetailResponse;
import com.inventory.product.rest.dto.response.InventorySummaryDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Extension-only writes and read-merge for vertical inventory fields via {@link PluginRegistry}.
 * Schema {@code storage: extension} fields are never persisted on core {@link Inventory}.
 */
@Component
@Slf4j
public class InventoryVerticalExtensionHandler {

  private final ShopRepository shopRepository;
  private final PluginRegistry pluginRegistry;
  private final SchemaLoader schemaLoader;

  public InventoryVerticalExtensionHandler(
      ShopRepository shopRepository,
      PluginRegistry pluginRegistry,
      SchemaLoader schemaLoader) {
    this.shopRepository = shopRepository;
    this.pluginRegistry = pluginRegistry;
    this.schemaLoader = schemaLoader;
  }

  /** Strip extension-schema fields from core entity before save (M7 — extension-only writes). */
  public void clearExtensionFieldsFromCore(String shopId, Inventory inventory) {
    ExtensionContext ctx = resolveContext(shopId).orElse(null);
    if (ctx == null || inventory == null || ctx.extensionFields().isEmpty()) {
      return;
    }
    BeanWrapper wrapper = new BeanWrapperImpl(inventory);
    for (VerticalSchemaField field : ctx.extensionFields()) {
      String property =
          StringUtils.hasText(field.getApiKey()) ? field.getApiKey() : field.getKey();
      if (wrapper.isWritableProperty(property)) {
        wrapper.setPropertyValue(property, null);
      }
    }
  }

  /** Load extension fields onto an in-memory inventory (invoices, reminders, internal reads). */
  public void enrichInventoryFromExtension(String shopId, Inventory inventory) {
    if (inventory == null || !StringUtils.hasText(inventory.getId())) {
      return;
    }
    ExtensionContext ctx = resolveContext(shopId).orElse(null);
    if (ctx == null) {
      return;
    }
    Map<String, Object> stored =
        ctx.repository()
            .findByInventoryId(shopId, inventory.getId())
            .orElse(Map.of());
    Map<String, Object> merged =
        VerticalSchemaStorage.mergeExtensionReadFields(
            ctx.extensionFields(), stored, inventory);
    VerticalSchemaStorage.applyExtensionFieldsToBean(inventory, merged);
  }

  public void persistAfterCreate(
      String shopId, Inventory inventory, CreateInventoryRequest request) {
    persistExtension(shopId, inventory, request, null);
  }

  public void persistAfterUpdate(
      String shopId, Inventory inventory, UpdateInventoryRequest request) {
    persistExtension(shopId, inventory, request, inventory);
  }

  private void persistExtension(
      String shopId, Inventory inventory, Object request, Inventory existingForMerge) {
    ExtensionContext ctx = resolveContext(shopId).orElse(null);
    if (ctx == null || inventory == null || !StringUtils.hasText(inventory.getId())) {
      return;
    }
    Map<String, Object> extensionFallback =
        existingForMerge != null
            ? ctx.repository()
                .findByInventoryId(shopId, inventory.getId())
                .orElse(Map.of())
            : Map.of();
    Map<String, Object> merged =
        VerticalSchemaFieldResolver.mergeVerticalFields(
            ctx.inventoryFields(), request, existingForMerge, extensionFallback);
    Map<String, Object> extensionFields =
        VerticalSchemaStorage.extractExtensionFields(ctx.inventoryFields(), merged);
    if (extensionFields.isEmpty()) {
      return;
    }
    ctx.repository().upsert(shopId, inventory.getId(), extensionFields);
    log.debug(
        "Persisted {} extension field(s) for inventory {} (vertical={})",
        extensionFields.size(),
        inventory.getId(),
        ctx.verticalId());
  }

  public InventoryDetailResponse mergeDetail(
      String shopId, Inventory inventory, InventoryDetailResponse detail) {
    if (detail == null || inventory == null) {
      return detail;
    }
    ExtensionContext ctx = resolveContext(shopId).orElse(null);
    if (ctx == null) {
      return detail;
    }
    Map<String, Object> stored =
        ctx.repository()
            .findByInventoryId(shopId, inventory.getId())
            .orElse(Map.of());
    applyMergedFields(detail, inventory, stored, ctx);
    return detail;
  }

  public InventorySummaryDto mergeSummary(
      String shopId, Inventory inventory, InventorySummaryDto summary) {
    if (summary == null || inventory == null) {
      return summary;
    }
    ExtensionContext ctx = resolveContext(shopId).orElse(null);
    if (ctx == null) {
      return summary;
    }
    Map<String, Object> stored =
        ctx.repository()
            .findByInventoryId(shopId, inventory.getId())
            .orElse(Map.of());
    applyMergedFields(summary, inventory, stored, ctx);
    return summary;
  }

  public void mergeDetails(
      String shopId, List<Inventory> inventories, List<InventoryDetailResponse> details) {
    if (inventories == null
        || details == null
        || inventories.isEmpty()
        || inventories.size() != details.size()) {
      return;
    }
    ExtensionContext ctx = resolveContext(shopId).orElse(null);
    if (ctx == null) {
      return;
    }
    List<String> ids = inventories.stream().map(Inventory::getId).toList();
    Map<String, Map<String, Object>> stored =
        ctx.repository().findByInventoryIds(shopId, ids);
    for (int i = 0; i < inventories.size(); i++) {
      Map<String, Object> fields =
          stored.getOrDefault(inventories.get(i).getId(), Map.of());
      applyMergedFields(details.get(i), inventories.get(i), fields, ctx);
    }
  }

  public void mergeSummaries(
      String shopId, List<Inventory> inventories, List<InventorySummaryDto> summaries) {
    if (inventories == null
        || summaries == null
        || inventories.isEmpty()
        || inventories.size() != summaries.size()) {
      return;
    }
    ExtensionContext ctx = resolveContext(shopId).orElse(null);
    if (ctx == null) {
      return;
    }
    List<String> ids = inventories.stream().map(Inventory::getId).toList();
    Map<String, Map<String, Object>> stored =
        ctx.repository().findByInventoryIds(shopId, ids);
    for (int i = 0; i < inventories.size(); i++) {
      Map<String, Object> fields =
          stored.getOrDefault(inventories.get(i).getId(), Map.of());
      applyMergedFields(summaries.get(i), inventories.get(i), fields, ctx);
    }
  }

  public Map<String, Object> loadExtensionFieldsForValidation(
      String shopId, String inventoryId) {
    ExtensionContext ctx = resolveContext(shopId).orElse(null);
    if (ctx == null || !StringUtils.hasText(inventoryId)) {
      return Map.of();
    }
    return ctx.repository().findByInventoryId(shopId, inventoryId).orElse(Map.of());
  }

  private void applyMergedFields(
      Object responseDto,
      Inventory inventory,
      Map<String, Object> storedExtension,
      ExtensionContext ctx) {
    Map<String, Object> merged =
        VerticalSchemaStorage.mergeExtensionReadFields(
            ctx.extensionFields(), storedExtension, inventory);
    if (merged.isEmpty()) {
      return;
    }
    if (responseDto instanceof InventoryDetailResponse detail) {
      detail.setVerticalFields(new LinkedHashMap<>(merged));
    } else if (responseDto instanceof InventorySummaryDto summary) {
      summary.setVerticalFields(new LinkedHashMap<>(merged));
    }
    VerticalSchemaStorage.applyExtensionFieldsToBean(responseDto, merged);
  }

  private Optional<ExtensionContext> resolveContext(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      return Optional.empty();
    }
    Shop shop = shopRepository.findById(shopId).orElse(null);
    if (shop == null || !StringUtils.hasText(shop.getVerticalId())) {
      return Optional.empty();
    }
    return pluginRegistry
        .find(shop.getVerticalId())
        .flatMap(p -> p.getInventoryExtensionRepository().map(repo -> buildContext(shop, repo)));
  }

  private ExtensionContext buildContext(Shop shop, InventoryExtensionRepository repository) {
    VerticalSchema schema = schemaLoader.load(shop.getVerticalId(), shop.getPluginVersion());
    List<VerticalSchemaField> inventoryFields = VerticalSchemaStorage.inventoryFields(schema);
    List<VerticalSchemaField> extensionFields =
        VerticalSchemaStorage.extensionFields(inventoryFields);
    return new ExtensionContext(
        shop.getVerticalId(), inventoryFields, extensionFields, repository);
  }

  private record ExtensionContext(
      String verticalId,
      List<VerticalSchemaField> inventoryFields,
      List<VerticalSchemaField> extensionFields,
      InventoryExtensionRepository repository) {}
}
