package com.inventory.documentservice.api;

/**
 * Supported printer layouts. Each type maps to a Thymeleaf template within a document family
 * (invoice, credit note, …); the template paths live with the renderers, in
 * {@code DocumentTemplateFamily#templateFor}.
 */
public enum PrinterType {
  /** Standard A4 (laser/inkjet). */
  NORMAL,

  /** Compact monospace layout on A4 for dot-matrix printers. */
  DOT_MATRIX,

  /** Narrow ~75mm thermal receipt roll. */
  THERMAL_3INCH;

  /**
   * Resolve a request string to a printer type. Unknown or blank values fall back to {@link #NORMAL}.
   */
  public static PrinterType from(String value) {
    if (value == null || value.isBlank()) {
      return NORMAL;
    }
    String normalized = value.trim();
    for (PrinterType type : values()) {
      if (type.name().equalsIgnoreCase(normalized)) {
        return type;
      }
    }
    return NORMAL;
  }
}
