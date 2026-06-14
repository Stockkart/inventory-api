package com.inventory.pluginengine;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

/** Reads extension field values from request {@code verticalFields} bags only. */
public final class VerticalFieldsReader {

  private VerticalFieldsReader() {}

  public static Map<String, Object> bagFrom(Object requestBean) {
    if (requestBean == null) {
      return Map.of();
    }
    BeanWrapper wrapper = new BeanWrapperImpl(requestBean);
    if (!wrapper.isReadableProperty("verticalFields")) {
      return Map.of();
    }
    Object raw = wrapper.getPropertyValue("verticalFields");
    if (!(raw instanceof Map<?, ?> bag) || bag.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    bag.forEach((k, v) -> {
      if (k != null && v != null) {
        out.put(String.valueOf(k), v);
      }
    });
    return Collections.unmodifiableMap(out);
  }

  public static Instant expiryDateFrom(Object requestBean) {
    Instant fromBag = asInstant(bagFrom(requestBean).get("expiryDate"));
    if (fromBag != null) {
      return fromBag;
    }
    return readInstantProperty(requestBean, "expiryDate");
  }

  public static String batchNoFrom(Object requestBean) {
    Object value = bagFrom(requestBean).get("batchNo");
    return value != null ? String.valueOf(value) : null;
  }

  public static Instant expiryDateFrom(Map<String, Object> extensionFields) {
    return asInstant(extensionFields != null ? extensionFields.get("expiryDate") : null);
  }

  public static String batchNoFrom(Map<String, Object> extensionFields) {
    if (extensionFields == null) {
      return null;
    }
    Object value = extensionFields.get("batchNo");
    return value != null ? String.valueOf(value) : null;
  }

  private static Instant readInstantProperty(Object requestBean, String property) {
    if (requestBean == null) {
      return null;
    }
    BeanWrapper wrapper = new BeanWrapperImpl(requestBean);
    if (!wrapper.isReadableProperty(property)) {
      return null;
    }
    return asInstant(wrapper.getPropertyValue(property));
  }

  private static Instant asInstant(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Instant instant) {
      return instant;
    }
    return ExtensionFieldCoercion.asInstant(value);
  }
}
