package com.inventory.pluginengine;

import com.inventory.pluginengine.schema.VerticalSchema;
import java.util.Map;

/**
 * Validates inventory create/update payloads against a vertical schema and business rules.
 */
public interface InventoryVerticalValidator {

  void validateCreate(InventoryValidationContext context);

  void validateUpdate(InventoryValidationContext context);

  /**
   * Flat field map keyed by schema field name (e.g. {@code batchNo}, {@code manufacturer}).
   * Core request DTOs may use different property names — plugins map as needed.
   */
  record InventoryValidationContext(
      String shopId,
      String verticalId,
      String pluginVersion,
      VerticalSchema schema,
      Map<String, Object> fields) {}
}
