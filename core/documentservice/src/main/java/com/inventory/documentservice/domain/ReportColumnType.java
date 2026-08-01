package com.inventory.documentservice.domain;

/**
 * How a report column's values are rendered.
 *
 * <p>Callers hand over raw {@code BigDecimal} / {@code LocalDate} values and this decides the
 * presentation, so currency and date formatting live in one place instead of being repeated by
 * every report that wants a PDF.
 */
public enum ReportColumnType {
  TEXT,
  /** Right-aligned currency; zero renders as an em dash in PDFs and stays numeric in Excel. */
  MONEY,
  DATE
}
