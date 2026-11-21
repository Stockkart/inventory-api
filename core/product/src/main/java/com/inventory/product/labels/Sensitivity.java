package com.inventory.product.labels;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether a catalog field's value is safe to show over the counter.
 *
 * <p>{@link #SHOP_INTERNAL} marks the shop's own figures (cost price, purchase discount, purchase
 * scheme) that staff may want on a card but a customer glancing at the Scan &amp; Sell screen must
 * not see. The frontend applies this through a visibility policy; the backend only classifies.
 * Serialized in uppercase to match the frontend {@code FieldSensitivity} type.
 */
public enum Sensitivity {
  PUBLIC,
  SHOP_INTERNAL;

  /** Name used in API payloads. */
  @JsonValue
  public String wireName() {
    return name();
  }
}
