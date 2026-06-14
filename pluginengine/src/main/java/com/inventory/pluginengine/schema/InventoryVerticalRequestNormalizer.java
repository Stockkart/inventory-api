package com.inventory.pluginengine.schema;

import java.time.Instant;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.util.StringUtils;

/**
 * Hoists {@code verticalFields} into top-level request properties when the flat field is empty.
 *
 * <p>Correct API shape (extension fields only in {@code verticalFields}) still needs this:
 * validators, reminders, and MapStruct read flat DTO properties ({@code getExpiryDate()}, etc.).
 * This does not write to Mongo — {@link com.inventory.product.service.vertical.InventoryVerticalExtensionHandler}
 * routes extension fields to {@code inventory_ext_*}.
 */
public final class InventoryVerticalRequestNormalizer {

  private InventoryVerticalRequestNormalizer() {}

  public static void normalizeCreate(Object request) {
    if (request == null) {
      return;
    }
    BeanWrapper wrapper = new BeanWrapperImpl(request);
    if (!wrapper.isReadableProperty("verticalFields")) {
      return;
    }
    Object rawBag = wrapper.getPropertyValue("verticalFields");
    if (!(rawBag instanceof java.util.Map<?, ?> bag) || bag.isEmpty()) {
      return;
    }
    bag.forEach(
        (key, value) -> {
          if (key == null || value == null) {
            return;
          }
          String property = String.valueOf(key);
          if (!wrapper.isWritableProperty(property)) {
            return;
          }
          Object current =
              wrapper.isReadableProperty(property) ? wrapper.getPropertyValue(property) : null;
          if (!isEmpty(current)) {
            return;
          }
          wrapper.setPropertyValue(property, coerce(wrapper, property, value));
        });
  }

  private static Object coerce(BeanWrapper wrapper, String property, Object value) {
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

  private static boolean isEmpty(Object value) {
    if (value == null) {
      return true;
    }
    if (value instanceof String s) {
      return !StringUtils.hasText(s);
    }
    return false;
  }
}
