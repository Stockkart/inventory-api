package com.inventory.documentservice.domain;

/**
 * Supported invoice printer layouts. Each type maps to a Thymeleaf template
 * optimized for that printer form factor.
 */
public enum PrinterType {
  /** Standard A4 tax invoice (laser/inkjet). */
  NORMAL("invoice/invoice"),

  /** Compact monospace layout on A4 for dot-matrix printers. */
  DOT_MATRIX("invoice/invoice-dotmatrix"),

  /** Narrow ~75mm thermal receipt roll. */
  THERMAL_3INCH("invoice/invoice-thermal-3inch");

  private final String templateName;

  PrinterType(String templateName) {
    this.templateName = templateName;
  }

  public String getTemplateName() {
    return templateName;
  }

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
