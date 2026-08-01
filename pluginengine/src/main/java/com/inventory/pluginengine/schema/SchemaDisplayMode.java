package com.inventory.pluginengine.schema;

public enum SchemaDisplayMode {
  REGULAR,
  BASIC,
  INVOICE,
  ONBOARDING;

  public static SchemaDisplayMode fromQuery(String mode) {
    if (mode == null || mode.isBlank()) {
      return REGULAR;
    }
    return switch (mode.trim().toLowerCase()) {
      case "basic" -> BASIC;
      case "invoice" -> INVOICE;
      case "onboarding" -> ONBOARDING;
      default -> REGULAR;
    };
  }
}
