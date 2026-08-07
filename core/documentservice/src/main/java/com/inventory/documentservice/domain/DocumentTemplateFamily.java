package com.inventory.documentservice.domain;

/**
 * Logical document families that share printer layouts but use different
 * Thymeleaf template bases. Extensible for future document types (debit notes, etc.).
 */
public enum DocumentTemplateFamily {
  INVOICE("invoice/invoice", "invoice/invoice-dotmatrix", "invoice/invoice-thermal-3inch"),
  CREDIT_NOTE(
      "credit-note/credit-note",
      "credit-note/credit-note-dotmatrix",
      "credit-note/credit-note-thermal-3inch");

  private final String normalTemplate;
  private final String dotMatrixTemplate;
  private final String thermal3InchTemplate;

  DocumentTemplateFamily(String normalTemplate, String dotMatrixTemplate, String thermal3InchTemplate) {
    this.normalTemplate = normalTemplate;
    this.dotMatrixTemplate = dotMatrixTemplate;
    this.thermal3InchTemplate = thermal3InchTemplate;
  }

  public String templateFor(PrinterType printerType) {
    PrinterType resolved = printerType != null ? printerType : PrinterType.NORMAL;
    return switch (resolved) {
      case DOT_MATRIX -> dotMatrixTemplate;
      case THERMAL_3INCH -> thermal3InchTemplate;
      case NORMAL -> normalTemplate;
    };
  }
}
