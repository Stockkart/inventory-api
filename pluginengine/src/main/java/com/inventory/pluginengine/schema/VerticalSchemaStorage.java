package com.inventory.pluginengine.schema;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.util.StringUtils;

/** Helpers for routing schema fields to core vs extension storage. */
public final class VerticalSchemaStorage {

  public static final String STORAGE_EXTENSION = "extension";
  public static final String STORAGE_CORE = "core";

  private VerticalSchemaStorage() {}

  public static List<VerticalSchemaField> inventoryFields(VerticalSchema schema) {
    if (schema == null || schema.getEntities() == null) {
      return List.of();
    }
    VerticalEntitySchema inventory = schema.getEntities().get("inventory");
    if (inventory == null || inventory.getFields() == null) {
      return List.of();
    }
    return inventory.getFields();
  }

  public static List<VerticalSchemaField> extensionFields(List<VerticalSchemaField> schemaFields) {
    if (schemaFields == null || schemaFields.isEmpty()) {
      return List.of();
    }
    return schemaFields.stream().filter(VerticalSchemaStorage::isExtensionStorage).toList();
  }

  public static Map<String, Object> extractExtensionFields(
      List<VerticalSchemaField> schemaFields, Map<String, Object> mergedFields) {
    if (mergedFields == null || mergedFields.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (VerticalSchemaField field : extensionFields(schemaFields)) {
      String key = field.getKey();
      if (mergedFields.containsKey(key)) {
        Object value = mergedFields.get(key);
        if (value != null) {
          out.put(key, value);
        }
      }
    }
    return out;
  }

  public static Map<String, Object> extractLegacyCoreFields(
      Object coreEntity, List<VerticalSchemaField> extensionSchemaFields) {
    if (coreEntity == null || extensionSchemaFields == null || extensionSchemaFields.isEmpty()) {
      return Map.of();
    }
    BeanWrapper core = new BeanWrapperImpl(coreEntity);
    Map<String, Object> out = new LinkedHashMap<>();
    for (VerticalSchemaField field : extensionSchemaFields) {
      String property = apiProperty(field);
      if (!core.isReadableProperty(property)) {
        continue;
      }
      Object value = core.getPropertyValue(property);
      if (value != null) {
        out.put(field.getKey(), value);
      }
    }
    return out;
  }

  public static Map<String, Object> mergeExtensionReadFields(
      List<VerticalSchemaField> extensionSchemaFields,
      Map<String, Object> storedExtension,
      Object legacyCoreEntity) {
    Map<String, Object> out = new LinkedHashMap<>();
    BeanWrapper core = legacyCoreEntity != null ? new BeanWrapperImpl(legacyCoreEntity) : null;
    for (VerticalSchemaField field : extensionSchemaFields) {
      String key = field.getKey();
      Object value = storedExtension != null ? storedExtension.get(key) : null;
      if (value == null && core != null) {
        String property = apiProperty(field);
        if (core.isReadableProperty(property)) {
          value = core.getPropertyValue(property);
        }
      }
      if (value != null) {
        out.put(key, value);
      }
    }
    return out;
  }

  public static void applyExtensionFieldsToBean(Object target, Map<String, Object> extensionFields) {
    if (target == null || extensionFields == null || extensionFields.isEmpty()) {
      return;
    }
    BeanWrapper wrapper = new BeanWrapperImpl(target);
    extensionFields.forEach(
        (key, value) -> {
          if (wrapper.isWritableProperty(key)) {
            wrapper.setPropertyValue(key, coerceForProperty(wrapper, key, value));
          }
        });
  }

  private static Object coerceForProperty(BeanWrapper wrapper, String property, Object value) {
    if (value == null) {
      return null;
    }
    Class<?> type = wrapper.getPropertyType(property);
    if (type == null) {
      return value;
    }
    if (Instant.class.isAssignableFrom(type)) {
      return com.inventory.pluginengine.ExtensionFieldCoercion.asInstant(value);
    }
    if (Integer.class.isAssignableFrom(type) || int.class.equals(type)) {
      return com.inventory.pluginengine.ExtensionFieldCoercion.asInteger(value);
    }
    if (String.class.isAssignableFrom(type)) {
      return com.inventory.pluginengine.ExtensionFieldCoercion.asString(value);
    }
    return value;
  }

  private static boolean isExtensionStorage(VerticalSchemaField field) {
    return field != null
        && StringUtils.hasText(field.getStorage())
        && STORAGE_EXTENSION.equalsIgnoreCase(field.getStorage().trim());
  }

  private static String apiProperty(VerticalSchemaField field) {
    return StringUtils.hasText(field.getApiKey()) ? field.getApiKey() : field.getKey();
  }
}
