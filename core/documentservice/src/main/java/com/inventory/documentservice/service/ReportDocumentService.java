package com.inventory.documentservice.service;

import com.inventory.documentservice.domain.ReportColumnType;
import com.inventory.documentservice.rest.dto.TabularReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders {@link TabularReport}s to PDF and Excel.
 *
 * <p>Reports previously assembled their own HTML with a {@code StringBuilder}, which meant every
 * new report re-implemented escaping, currency formatting and page setup. This routes them through
 * the same Thymeleaf + OpenHTMLToPDF pipeline as invoices, so markup lives in a template and
 * escaping is handled by the engine rather than a hand-rolled replace chain.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReportDocumentService {

  private static final String TEMPLATE = "report/tabular-report";
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

  /** Shown instead of a zero amount so dense money columns stay readable. */
  private static final String EMPTY_MONEY = "—";

  private final TemplateEngine templateEngine;
  private final InvoicePdfService invoicePdfService;
  private final DocumentService documentService;

  /** A rendered cell: its display text plus whether it should be right-aligned. */
  public record RenderedCell(String text, boolean numeric) {}

  public byte[] renderPdf(TabularReport report) {
    log.info("Rendering PDF report '{}' ({} rows)", report.documentTitle(), report.rows().size());
    return invoicePdfService.convertHtmlToPdf(renderHtml(report));
  }

  /** Same markup as the PDF, for preview or debugging. */
  public String renderHtml(TabularReport report) {
    Context context = new Context();
    context.setVariable("documentTitle", report.documentTitle());
    context.setVariable("heading", report.heading());
    context.setVariable("subtitle", report.subtitle());
    context.setVariable("columns", renderColumns(report));
    context.setVariable("rows", renderRows(report));
    context.setVariable("totals", renderTotals(report));
    context.setVariable(
        "summaryNotes",
        report.summaryNotes() == null || report.summaryNotes().isEmpty()
            ? null
            : report.summaryNotes());
    return templateEngine.process(TEMPLATE, context);
  }

  public byte[] renderExcel(TabularReport report) {
    log.info("Rendering Excel report '{}' ({} rows)", report.documentTitle(), report.rows().size());
    return documentService.generateExcel(
        report.documentTitle(),
        report.heading(),
        report.subtitle(),
        report.headerLabels(),
        excelRows(report),
        report.totalsRow());
  }

  private List<RenderedCell> renderColumns(TabularReport report) {
    return report.columns().stream()
        .map(c -> new RenderedCell(c.label(), c.type() == ReportColumnType.MONEY))
        .toList();
  }

  private List<List<RenderedCell>> renderRows(TabularReport report) {
    List<List<RenderedCell>> rendered = new ArrayList<>(report.rows().size());
    for (List<Object> row : report.rows()) {
      rendered.add(renderRow(report, row));
    }
    return rendered;
  }

  private List<RenderedCell> renderTotals(TabularReport report) {
    if (report.totalsRow() == null || report.totalsRow().isEmpty()) {
      return null;
    }
    return renderRow(report, report.totalsRow());
  }

  private List<RenderedCell> renderRow(TabularReport report, List<Object> row) {
    List<RenderedCell> cells = new ArrayList<>(row.size());
    for (int i = 0; i < row.size(); i++) {
      ReportColumnType type = columnTypeAt(report, i);
      cells.add(new RenderedCell(format(row.get(i), type), type == ReportColumnType.MONEY));
    }
    return cells;
  }

  /**
   * Excel keeps money numeric so the sheet can total it; dates become display strings because a
   * pre-formatted date is what the previous export produced and what users sort on.
   */
  private List<List<Object>> excelRows(TabularReport report) {
    List<List<Object>> rows = new ArrayList<>(report.rows().size());
    for (List<Object> row : report.rows()) {
      List<Object> cells = new ArrayList<>(row.size());
      for (int i = 0; i < row.size(); i++) {
        Object value = row.get(i);
        cells.add(
            columnTypeAt(report, i) == ReportColumnType.MONEY ? value : format(value, columnTypeAt(report, i)));
      }
      rows.add(cells);
    }
    return rows;
  }

  /** Columns can be shorter than a row if a caller mis-sizes it; treat the overflow as text. */
  private ReportColumnType columnTypeAt(TabularReport report, int index) {
    return index < report.columns().size() ? report.columns().get(index).type() : ReportColumnType.TEXT;
  }

  private String format(Object value, ReportColumnType type) {
    if (value == null) {
      return type == ReportColumnType.MONEY ? EMPTY_MONEY : "";
    }
    return switch (type) {
      case MONEY -> formatMoney(value);
      case DATE -> value instanceof LocalDate date ? date.format(DATE_FORMAT) : String.valueOf(value);
      case TEXT -> String.valueOf(value);
    };
  }

  private String formatMoney(Object value) {
    if (!(value instanceof BigDecimal amount)) {
      return String.valueOf(value);
    }
    if (amount.signum() == 0) {
      return EMPTY_MONEY;
    }
    // Constructed per call: NumberFormat is not thread-safe and this is shared across requests.
    return NumberFormat.getCurrencyInstance(Locale.of("en", "IN")).format(amount);
  }
}
