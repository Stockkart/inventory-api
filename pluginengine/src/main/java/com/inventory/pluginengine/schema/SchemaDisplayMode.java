package com.inventory.pluginengine.schema;

public enum SchemaDisplayMode {
  REGULAR,
  BASIC,
  INVOICE;

  public static SchemaDisplayMode fromQuery(String mode) {
    if (mode == null || mode.isBlank()) {
      return REGULAR;
    }
    return switch (mode.trim().toLowerCase()) {
      case "basic" -> BASIC;
      case "invoice" -> INVOICE;
      default -> REGULAR;
    };
  }
}
