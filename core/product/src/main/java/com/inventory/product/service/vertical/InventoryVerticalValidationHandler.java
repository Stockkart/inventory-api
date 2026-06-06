package com.inventory.product.service.vertical;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.pluginengine.InventoryVerticalValidator.InventoryValidationContext;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.CreateInventoryRequest;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves shop vertical and delegates inventory validation to the registered plugin validator.
 * Mirrors the pricing handler pattern — minimal change to {@code InventoryService} entry points.
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
    resolveAndValidate(shopId, fieldsFromCreateRequest(request), true);
  }

  public void validateUpdate(String shopId, Inventory existing, UpdateInventoryRequest request) {
    resolveAndValidate(shopId, mergedUpdateFields(existing, request), false);
  }

  private void resolveAndValidate(String shopId, Map<String, Object> fields, boolean create) {
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

  private static Map<String, Object> fieldsFromCreateRequest(CreateInventoryRequest request) {
    Map<String, Object> fields = new HashMap<>();
    fields.put("name", request.getName());
    fields.put("barcode", request.getBarcode());
    fields.put("batchNo", request.getBatchNo());
    fields.put("expiryDate", request.getExpiryDate());
    fields.put("companyName", request.getCompanyName());
    fields.put("manufacturer", request.getCompanyName());
    fields.put("hsn", request.getHsn());
    fields.put("baseUnit", request.getBaseUnit());
    fields.put("qty", request.getCount());
    fields.put("rate", request.getMaximumRetailPrice());
    fields.put("cgst", request.getCgst());
    fields.put("sgst", request.getSgst());
    mergeVerticalFields(fields, request.getVerticalFields());
    return fields;
  }

  private static Map<String, Object> mergedUpdateFields(
      Inventory existing, UpdateInventoryRequest request) {
    Map<String, Object> fields = new HashMap<>();
    fields.put("name", request.getName() != null ? request.getName() : existing.getName());
    fields.put(
        "batchNo", request.getBatchNo() != null ? request.getBatchNo() : existing.getBatchNo());
    fields.put(
        "expiryDate",
        request.getExpiryDate() != null ? request.getExpiryDate() : existing.getExpiryDate());
    String companyName =
        request.getCompanyName() != null ? request.getCompanyName() : existing.getCompanyName();
    fields.put("companyName", companyName);
    fields.put("manufacturer", companyName);
    fields.put(
        "barcode", request.getBarcode() != null ? request.getBarcode() : existing.getBarcode());
    fields.put("hsn", request.getHsn() != null ? request.getHsn() : existing.getHsn());
    fields.put(
        "baseUnit", request.getBaseUnit() != null ? request.getBaseUnit() : existing.getBaseUnit());
    if (request.getCgst() != null) {
      fields.put("cgst", request.getCgst());
    }
    if (request.getSgst() != null) {
      fields.put("sgst", request.getSgst());
    }
    mergeVerticalFields(fields, request.getVerticalFields());
    return fields;
  }

  private static void mergeVerticalFields(
      Map<String, Object> fields, Map<String, Object> verticalFields) {
    if (verticalFields != null && !verticalFields.isEmpty()) {
      fields.putAll(verticalFields);
    }
  }
}
