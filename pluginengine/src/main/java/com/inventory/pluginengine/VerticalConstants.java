package com.inventory.pluginengine;

public final class VerticalConstants {

  public static final String MEDICAL = "medical";
  public static final String SPORTS = "sports";
  public static final String DEFAULT_PLUGIN_VERSION = "1.0.0";
  public static final String SCHEMA_STATUS_ACTIVE = "ACTIVE";

  public static boolean isKnownVertical(String verticalId) {
    if (verticalId == null || verticalId.isBlank()) {
      return false;
    }
    String id = verticalId.trim().toLowerCase();
    return MEDICAL.equals(id) || SPORTS.equals(id);
  }

  private VerticalConstants() {}
}
