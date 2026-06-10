package com.inventory.product.service.vertical;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.pluginengine.InventoryVerticalValidator.InventoryValidationContext;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pluginengine.schema.VerticalSchemaFieldResolver;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.CreateInventoryRequest;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves shop vertical and delegates inventory validation to the registered plugin validator.
 * Field values are built from {@code verticalFields} and request/entity properties via the schema resolver.
 */
@Component
@Slf4j
public class InventoryVerticalValidationHandler {

  private final ShopRepository shopRepository;
  private final PluginRegistry pluginRegistry;
  private final SchemaLoader schemaLoader;

  public InventoryVerticalValidationHandler(
      ShopRepository shopRepository, PluginRegistry pluginRegistry, SchemaLoader schemaLoader) {
    this.shopRepository = shopRepository;
    this.pluginRegistry = pluginRegistry;
    this.schemaLoader = schemaLoader;
  }

  public void validateCreate(String shopId, CreateInventoryRequest request) {
    resolveAndValidate(shopId, request, null, true);
  }

  public void validateUpdate(String shopId, Inventory existing, UpdateInventoryRequest request) {
    resolveAndValidate(shopId, request, existing, false);
  }

  private void resolveAndValidate(
      String shopId, Object requestBean, Inventory existing, boolean create) {
    Shop shop =
        shopRepository
            .findById(shopId)
            .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));
    if (!StringUtils.hasText(shop.getVerticalId())) {
      log.debug("Shop {} has no verticalId — skip vertical validation", shopId);
      return;
    }

    pluginRegistry
        .find(shop.getVerticalId())
        .flatMap(p -> p.getInventoryValidator())
        .ifPresent(
            validator -> {
              VerticalSchema schema =
                  schemaLoader.load(shop.getVerticalId(), shop.getPluginVersion());
              List<VerticalSchemaField> inventoryFields = inventoryFields(schema);
              Map<String, Object> fields =
                  VerticalSchemaFieldResolver.mergeVerticalFields(
                      inventoryFields, requestBean, existing);

              InventoryValidationContext context =
                  new InventoryValidationContext(
                      shopId,
                      shop.getVerticalId(),
                      shop.getPluginVersion(),
                      schema,
                      fields);
              if (create) {
                validator.validateCreate(context);
              } else {
                validator.validateUpdate(context);
              }
            });
  }

  private static List<VerticalSchemaField> inventoryFields(VerticalSchema schema) {
    if (schema.getEntities() == null) {
      return List.of();
    }
    VerticalEntitySchema inventory = schema.getEntities().get("inventory");
    if (inventory == null || inventory.getFields() == null) {
      return List.of();
    }
    return inventory.getFields();
  }
}
