package com.inventory.documentservice.domain;

/**
 * Supported printer layouts. Each type maps to a Thymeleaf template within a
 * {@link DocumentTemplateFamily} (invoice, credit note, …).
 */
public enum PrinterType {
  /** Standard A4 (laser/inkjet). */
  NORMAL,

  /** Compact monospace layout on A4 for dot-matrix printers. */
  DOT_MATRIX,

  /** Narrow ~75mm thermal receipt roll. */
  THERMAL_3INCH;

  /**
   * Invoice template path (backward-compatible with existing callers).
   */
  public String getTemplateName() {
    return DocumentTemplateFamily.INVOICE.templateFor(this);
  }

  /**
   * Resolve a template for a document family + this printer layout.
   */
  public String getTemplateName(DocumentTemplateFamily family) {
    DocumentTemplateFamily resolved =
        family != null ? family : DocumentTemplateFamily.INVOICE;
    return resolved.templateFor(this);
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
