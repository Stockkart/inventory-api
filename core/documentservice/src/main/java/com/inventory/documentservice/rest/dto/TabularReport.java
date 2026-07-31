package com.inventory.documentservice.rest.dto;

import com.inventory.documentservice.domain.ReportColumnType;
import java.util.List;

/**
 * A report expressed as columns and rows, independent of output format.
 *
 * <p>Callers describe <em>what</em> the report contains; the document service decides how it looks
 * in PDF and Excel. Values are raw ({@code BigDecimal}, {@code LocalDate}, {@code String}) so both
 * renderers can present them appropriately — notably Excel keeps money numeric rather than
 * receiving a pre-formatted string it cannot total.
 *
 * @param documentTitle worksheet name and PDF document title
 * @param heading top line printed on the PDF
 * @param subtitle smaller meta line under the heading; null to omit
 * @param columns column definitions, in display order
 * @param rows row values, positionally matching {@code columns}
 * @param totalsRow optional totals appended after a blank line; null to omit
 * @param summaryNotes optional footer lines rendered under the table in the PDF
 */
public record TabularReport(
    String documentTitle,
    String heading,
    String subtitle,
    List<Column> columns,
    List<List<Object>> rows,
    List<Object> totalsRow,
    List<String> summaryNotes) {

  /** A single column: its header text and how to render its values. */
  public record Column(String label, ReportColumnType type) {

    public static Column text(String label) {
      return new Column(label, ReportColumnType.TEXT);
    }

    public static Column money(String label) {
      return new Column(label, ReportColumnType.MONEY);
    }

    public static Column date(String label) {
      return new Column(label, ReportColumnType.DATE);
    }
  }

  public List<String> headerLabels() {
    return columns.stream().map(Column::label).toList();
  }
}
