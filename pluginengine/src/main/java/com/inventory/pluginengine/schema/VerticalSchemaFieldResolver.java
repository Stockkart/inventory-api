package com.inventory.pluginengine.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.util.StringUtils;

/**
 * Builds a flat map keyed by schema field name from request/entity beans using {@code apiKey} (or
 * {@code key}) as the Java property name.
 */
public final class VerticalSchemaFieldResolver {

  private VerticalSchemaFieldResolver() {}

  public static Map<String, Object> resolve(
      List<VerticalSchemaField> schemaFields, Object primaryBean, Object fallbackBean) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (schemaFields == null || schemaFields.isEmpty()) {
      return out;
    }
    BeanWrapper primary = wrap(primaryBean);
    BeanWrapper fallback = wrap(fallbackBean);
    for (VerticalSchemaField field : schemaFields) {
      String property = apiProperty(field);
      Object value = readProperty(primary, property);
      if (value == null && fallback != null) {
        value = readProperty(fallback, property);
      }
      if (value != null) {
        out.put(field.getKey(), value);
      }
    }
    return out;
  }

  public static void mergeVerticalFields(Map<String, Object> fields, Object requestBean) {
    if (requestBean == null || fields == null) {
      return;
    }
    BeanWrapper wrapper = wrap(requestBean);
    if (!wrapper.isReadableProperty("verticalFields")) {
      return;
    }
    Object raw = wrapper.getPropertyValue("verticalFields");
    if (raw instanceof Map<?, ?> verticalFields && !verticalFields.isEmpty()) {
      verticalFields.forEach((k, v) -> fields.put(String.valueOf(k), v));
    }
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
