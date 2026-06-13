package com.inventory.pluginengine.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.util.StringUtils;

/**
 * Builds a flat map keyed by schema field name for vertical validation.
 *
 * <p>Priority per field: {@code verticalFields} on the request, then the request bean property
 * ({@code apiKey} or {@code key}), then the fallback entity on update.
 */
public final class VerticalSchemaFieldResolver {

  private VerticalSchemaFieldResolver() {}

  public static Map<String, Object> mergeVerticalFields(
      List<VerticalSchemaField> schemaFields, Object requestBean, Object fallbackBean) {
    return mergeVerticalFields(schemaFields, requestBean, fallbackBean, null);
  }

  /**
   * Like {@link #mergeVerticalFields(List, Object, Object)} but fills missing extension keys from
   * {@code extensionFallback} (existing extension document) when absent on request/core entity.
   */
  public static Map<String, Object> mergeVerticalFields(
      List<VerticalSchemaField> schemaFields,
      Object requestBean,
      Object fallbackBean,
      Map<String, Object> extensionFallback) {
    Map<String, Object> out = mergeVerticalFieldsInternal(schemaFields, requestBean, fallbackBean);
    if (extensionFallback == null || extensionFallback.isEmpty()) {
      return out;
    }
    for (VerticalSchemaField field : schemaFields) {
      if (!VerticalSchemaStorage.STORAGE_EXTENSION.equalsIgnoreCase(
          field.getStorage() != null ? field.getStorage().trim() : "")) {
        continue;
      }
      String key = field.getKey();
      if (!out.containsKey(key) && extensionFallback.containsKey(key)) {
        Object value = extensionFallback.get(key);
        if (value != null) {
          out.put(key, value);
        }
      }
    }
    return out;
  }

  private static Map<String, Object> mergeVerticalFieldsInternal(
      List<VerticalSchemaField> schemaFields, Object requestBean, Object fallbackBean) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (schemaFields == null || schemaFields.isEmpty()) {
      return out;
    }

    BeanWrapper request = wrap(requestBean);
    BeanWrapper fallback = wrap(fallbackBean);

    if (request != null && request.isReadableProperty("verticalFields")) {
      Object raw = request.getPropertyValue("verticalFields");
      if (raw instanceof Map<?, ?> verticalFields && !verticalFields.isEmpty()) {
        verticalFields.forEach((k, v) -> out.put(String.valueOf(k), v));
      }
    }

    for (VerticalSchemaField field : schemaFields) {
      String key = field.getKey();
      if (out.containsKey(key)) {
        continue;
      }
      String property = apiProperty(field);
      Object value = readProperty(request, property);
      if (value == null) {
        value = readProperty(fallback, property);
      }
      if (value != null) {
        out.put(key, value);
      }
    }

    return out;
  }

  private static String apiProperty(VerticalSchemaField field) {
    return StringUtils.hasText(field.getApiKey()) ? field.getApiKey() : field.getKey();
  }

  private static BeanWrapper wrap(Object bean) {
    return bean != null ? new BeanWrapperImpl(bean) : null;
  }

  private static Object readProperty(BeanWrapper wrapper, String property) {
    if (wrapper == null || !wrapper.isReadableProperty(property)) {
      return null;
    }
    return wrapper.getPropertyValue(property);
  }
}
