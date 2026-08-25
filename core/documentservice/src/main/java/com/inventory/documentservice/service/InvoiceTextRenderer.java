package com.inventory.documentservice.service;

import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.rest.dto.InvoiceItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;

/**
 * Renders a {@link GenerateInvoiceRequest} as fixed-width plain text for dot matrix printers.
 *
 * <p>Emits newlines only and never emits control characters. Carriage returns, page control and
 * ESC/P escape sequences are added downstream by the print bridge, not here.
 */
@Service
public class InvoiceTextRenderer {

  /** Invoice width in characters. 80 columns at 10 CPI is 8 inches. */
  public static final int LINE_WIDTH = 80;

  private static final String RULE_HEAVY = "=".repeat(LINE_WIDTH);
  private static final String RULE_LIGHT = "-".repeat(LINE_WIDTH);

  /** One item-table column. A negative width marks the flexible column. */
  private record Column(String header, int width, boolean rightAlign,
      BiFunction<InvoiceItem, Integer, String> value) {}

  public String render(GenerateInvoiceRequest request) {
    List<String> out = new ArrayList<>();
    appendHeader(out, request);
    appendMeta(out, request);
    appendItems(out, request);
    appendTaxSummary(out, request);
    appendTotals(out, request);
    appendFooter(out, request);
    return String.join("\n", out) + "\n";
  }

  private void appendHeader(List<String> out, GenerateInvoiceRequest r) {
    out.add(RULE_HEAVY);
    if (visible(r.getShowShopName())) {
      addCentredIfPresent(out, r.getShopName());
    }
    if (visible(r.getShowShopAddress())) {
      addCentredIfPresent(out, r.getShopAddress());
    }
    List<String> ids = new ArrayList<>();
    if (visible(r.getShowShopPhone()) && present(r.getShopPhone())) {
      ids.add("Ph: " + r.getShopPhone());
    }
    if (visible(r.getShowShopGstin()) && present(r.getShopGstin())) {
      ids.add("GSTIN: " + r.getShopGstin());
    }
    if (visible(r.getShowShopDlNo()) && present(r.getShopDlNo())) {
      ids.add("DL: " + r.getShopDlNo());
    }
    if (!ids.isEmpty()) {
      addCentredIfPresent(out, String.join(" | ", ids));
    }
    out.add(RULE_HEAVY);
  }

  private void appendMeta(List<String> out, GenerateInvoiceRequest r) {
    String date = join(" ", r.getInvoiceDate(), r.getInvoiceTime());
    out.add(twoColumn("Invoice No: " + nullToEmpty(r.getInvoiceNo()), "Date: " + date));
    String left = "";
    String right = "";
    if (visible(r.getShowCustomerName()) && present(r.getCustomerName())) {
      left = "Customer  : " + r.getCustomerName();
    }
    if (visible(r.getShowCustomerPhone()) && present(r.getCustomerPhone())) {
      right = "Ph  : " + r.getCustomerPhone();
    }
    if (!left.isEmpty() || !right.isEmpty()) {
      out.add(twoColumn(left, right));
    }
    out.add(RULE_LIGHT);
  }

  private void appendItems(List<String> out, GenerateInvoiceRequest r) {
    List<Column> columns = buildColumns(r);
    int flexWidth = flexWidth(columns);
    out.add(renderHeaderRow(columns, flexWidth));
    out.add(RULE_LIGHT);
    List<InvoiceItem> items = r.getItems() != null ? r.getItems() : List.of();
    for (int i = 0; i < items.size(); i++) {
      out.add(renderItemRow(columns, flexWidth, items.get(i), i + 1));
    }
    out.add(RULE_LIGHT);
  }

  private List<Column> buildColumns(GenerateInvoiceRequest r) {
    List<Column> columns = new ArrayList<>();
    columns.add(new Column("#", 2, true, (item, index) -> String.valueOf(index)));
    columns.add(new Column("PARTICULARS", -1, false, (item, index) -> nullToEmpty(item.getName())));
    if (visible(r.getShowBatch())) {
      columns.add(new Column("BATCH", 10, false, (item, index) -> nullToEmpty(item.getBatchNo())));
    }
    if (visible(r.getShowExpiry())) {
      columns.add(
          new Column("EXPIRY", 7, false, (item, index) -> nullToEmpty(item.getExpiryDate())));
    }
    columns.add(new Column("QTY", 5, true, (item, index) -> money(item.getQuantity())));
    if (visible(r.getShowMrp())) {
      columns.add(new Column("MRP", 8, true, (item, index) -> money(item.getMaximumRetailPrice())));
    }
    columns.add(new Column("RATE", 8, true, (item, index) -> money(item.getPriceToRetail())));
    columns.add(new Column("AMOUNT", 9, true, (item, index) -> money(item.getTotalAmount())));
    return columns;
  }

  /** Width the flexible PARTICULARS column absorbs. */
  private int flexWidth(List<Column> columns) {
    int fixed = columns.stream().filter(c -> c.width() > 0).mapToInt(Column::width).sum();
    return LINE_WIDTH - fixed - (columns.size() - 1);
  }

  private String renderHeaderRow(List<Column> columns, int flexWidth) {
    List<String> cells = new ArrayList<>();
    for (Column column : columns) {
      int width = column.width() > 0 ? column.width() : flexWidth;
      cells.add(pad(column.header(), width, column.rightAlign()));
    }
    return String.join(" ", cells);
  }

  private String renderItemRow(List<Column> columns, int flexWidth, InvoiceItem item, int index) {
    List<String> cells = new ArrayList<>();
    for (Column column : columns) {
      int width = column.width() > 0 ? column.width() : flexWidth;
      cells.add(pad(column.value().apply(item, index), width, column.rightAlign()));
    }
    return String.join(" ", cells);
  }

  private void appendTaxSummary(List<String> out, GenerateInvoiceRequest r) {
    if (!visible(r.getShowTaxDetails())) {
      return;
    }
    Map<String, BigDecimal[]> byRate = new LinkedHashMap<>();
    List<InvoiceItem> items = r.getItems() != null ? r.getItems() : List.of();
    for (InvoiceItem item : items) {
      BigDecimal rate = rateOf(item);
      BigDecimal amount = item.getTotalAmount() != null ? item.getTotalAmount() : BigDecimal.ZERO;
      BigDecimal taxable =
          amount
              .multiply(BigDecimal.valueOf(100))
              .divide(BigDecimal.valueOf(100).add(rate), 2, RoundingMode.HALF_UP);
      BigDecimal tax = amount.subtract(taxable);
      BigDecimal half = tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
      BigDecimal[] row =
          byRate.computeIfAbsent(
              money(rate),
              key ->
                  new BigDecimal[] {
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
                  });
      row[0] = row[0].add(taxable);
      row[1] = row[1].add(half);
      row[2] = row[2].add(half);
      row[3] = row[3].add(tax);
    }
    if (byRate.isEmpty()) {
      return;
    }
    out.add(
        String.format("%-8s %12s %11s %11s %12s", "GST%", "TAXABLE", "CGST", "SGST", "TOTAL TAX"));
    byRate.forEach(
        (rate, row) ->
            out.add(
                String.format(
                    "%-8s %12s %11s %11s %12s",
                    rate, money(row[0]), money(row[1]), money(row[2]), money(row[3]))));
    out.add(RULE_LIGHT);
  }

  private void appendTotals(List<String> out, GenerateInvoiceRequest r) {
    out.add(totalRow("Sub Total :", r.getSubTotal()));
    if (isPositive(r.getDiscountTotal())) {
      out.add(totalRow("Discount :", r.getDiscountTotal()));
    }
    if (visible(r.getShowTaxDetails())) {
      out.add(totalRow("CGST :", r.getCgstAmount()));
      out.add(totalRow("SGST :", r.getSgstAmount()));
    }
    if (isNonZero(r.getRoundOff())) {
      out.add(totalRow("Round Off :", r.getRoundOff()));
    }
    out.add(totalRow("GRAND TOTAL :", r.getGrandTotal()));
    out.add(RULE_LIGHT);
  }

  private void appendFooter(List<String> out, GenerateInvoiceRequest r) {
    if (visible(r.getShowAmountInWords()) && present(r.getAmountInWords())) {
      out.addAll(wrap("Amount in words: " + r.getAmountInWords()));
    }
    if (present(r.getFooterNote())) {
      out.addAll(wrap(r.getFooterNote()));
    }
    if (visible(r.getShowSignatures())) {
      out.add("");
      out.add(pad("For " + nullToEmpty(r.getShopName()), LINE_WIDTH - 8, true));
      out.add("");
      out.add(pad("Authorised Signatory", LINE_WIDTH - 8, true));
    }
    out.add(RULE_HEAVY);
  }

  private static String totalRow(String label, BigDecimal amount) {
    return String.format("%68s%12s", label, money(amount));
  }

  private static BigDecimal rateOf(InvoiceItem item) {
    if (item.getGstPercent() != null) {
      return item.getGstPercent();
    }
    return parseRate(item.getCgst()).add(parseRate(item.getSgst()));
  }

  private static BigDecimal parseRate(String rate) {
    if (rate == null || rate.isBlank()) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(rate.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  /** Money with two decimals. A null value renders blank, never 0.00. */
  private static String money(BigDecimal value) {
    return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static String pad(String raw, int width, boolean rightAlign) {
    String value = clean(raw);
    if (value.length() > width) {
      return value.substring(0, width);
    }
    String filler = " ".repeat(width - value.length());
    return rightAlign ? filler + value : value + filler;
  }

  private static String twoColumn(String left, String right) {
    String l = clean(left);
    String r = clean(right);
    int gap = Math.max(1, LINE_WIDTH - l.length() - r.length());
    String line = l + " ".repeat(gap) + r;
    return line.length() > LINE_WIDTH ? line.substring(0, LINE_WIDTH) : line;
  }

  /**
   * Sanitises a field for placement into a fixed-width cell, regardless of which caller supplied
   * it: control characters (CR, LF, tab, ...) become a space so they cannot be mistaken for line
   * structure downstream, then every remaining non-printable-ASCII character is folded to {@code
   * '?'} so Java's column count agrees with the Go encoder's rune-based fold and count.
   *
   * <p>Order matters: control characters are neutralised to spaces first so a bare newline never
   * reaches the fold step and gets turned into {@code '?'} instead of whitespace.
   */
  private static String clean(String raw) {
    return foldNonAscii(sanitizeControlChars(nullToEmpty(raw)));
  }

  /** Replaces CR, LF and every other C0/DEL control character with a single space. */
  private static String sanitizeControlChars(String value) {
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      sb.append(c < 0x20 || c == 0x7F ? ' ' : c);
    }
    return sb.toString();
  }

  /**
   * Folds every character outside printable ASCII (0x20-0x7E) to {@code '?'}, matching the Go
   * encoder's fold. Iterates by code point so an astral-plane character (2 UTF-16 code units in
   * Java, 1 rune in Go) collapses to a single {@code '?'} instead of two, keeping Java's {@link
   * String#length()} equal to the Go encoder's rune count for the same text.
   */
  private static String foldNonAscii(String value) {
    StringBuilder sb = new StringBuilder(value.length());
    value.codePoints().forEach(cp -> sb.append(cp >= 0x20 && cp <= 0x7E ? (char) cp : '?'));
    return sb.toString();
  }

  private static void addCentredIfPresent(List<String> out, String raw) {
    if (!present(raw)) {
      return;
    }
    for (String line : wrap(raw)) {
      int left = Math.max(0, (LINE_WIDTH - line.length()) / 2);
      out.add(" ".repeat(left) + line);
    }
  }

  /** Greedy word wrap at LINE_WIDTH. Words longer than the width are hard-split. */
  private static List<String> wrap(String raw) {
    List<String> lines = new ArrayList<>();
    String value = nullToEmpty(raw).trim();
    if (value.isEmpty()) {
      return lines;
    }
    StringBuilder current = new StringBuilder();
    for (String token : value.split("\\s+")) {
      // Fold after splitting on whitespace: \s+ already consumes CR/LF/tab as separators, so
      // folding here only ever touches non-whitespace characters (e.g. emoji) and cannot turn a
      // newline into a literal '?' that would then fail to split.
      String word = foldNonAscii(token);
      while (word.length() > LINE_WIDTH) {
        if (current.length() > 0) {
          lines.add(current.toString());
          current.setLength(0);
        }
        lines.add(word.substring(0, LINE_WIDTH));
        word = word.substring(LINE_WIDTH);
      }
      if (current.length() == 0) {
        current.append(word);
      } else if (current.length() + 1 + word.length() <= LINE_WIDTH) {
        current.append(' ').append(word);
      } else {
        lines.add(current.toString());
        current.setLength(0);
        current.append(word);
      }
    }
    if (current.length() > 0) {
      lines.add(current.toString());
    }
    return lines;
  }

  private static String join(String separator, String a, String b) {
    if (!present(a)) {
      return nullToEmpty(b);
    }
    if (!present(b)) {
      return a;
    }
    return a + separator + b;
  }

  private static boolean isPositive(BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) > 0;
  }

  private static boolean isNonZero(BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) != 0;
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /** Visibility flags default to visible when unset, matching InvoicePdfService. */
  private static boolean visible(Boolean flag) {
    return flag == null || flag;
  }
}
