package com.inventory.documentservice.service;

import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.rest.dto.InvoiceItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Renders a {@link GenerateInvoiceRequest} as fixed-width plain text for dot matrix printers.
 *
 * <p>This is a line-for-line transcription of {@code invoice/invoice-dotmatrix.html} — the same
 * template the shop configures on the Invoice settings screen and sees in its live preview,
 * which is a server-rendered iframe of that very file. The two must agree: a shop that turns
 * GSTIN off, or sets the estimate header, expects the paper to follow. An earlier version of
 * this renderer invented its own layout, and printed an estimate under an "Invoice No" heading
 * with a "Round Off" line the template has no concept of.
 *
 * <p>Emits newlines only and never emits control characters. Carriage returns, page control and
 * ESC/P escape sequences are added downstream by the print bridge, not here.
 */
@Service
public class InvoiceTextRenderer {

  /** Invoice width in characters. 80 columns at 10 CPI is 8 inches. */
  public static final int LINE_WIDTH = 80;

  /** Half the width, for the two-column header the template lays out as a table row. */
  private static final int HALF = LINE_WIDTH / 2;

  private static final String RULE = "-".repeat(LINE_WIDTH);

  /**
   * The item columns, mirroring the template's widths (42/10/12/12/12/12 of the table). MRP and
   * Disc are conditional there, so they are conditional here; Item takes whatever is left.
   */
  private record Column(String header, int width, boolean rightAlign,
      Function<InvoiceItem, String> value) {}

  public String render(GenerateInvoiceRequest request) {
    List<String> out = new ArrayList<>();
    boolean estimate = isEstimate(request);

    appendTitle(out, estimate);
    appendPartiesRow(out, request, estimate);
    appendBuyer(out, request);
    appendItems(out, request);
    appendTotals(out, request);
    appendFooter(out, request);

    return String.join("\n", out) + "\n";
  }

  // ----------------------------------------------------------------- header

  /** The template prints a centred "Estimate" heading, and nothing at all for an invoice. */
  private void appendTitle(List<String> out, boolean estimate) {
    if (estimate) {
      out.add(centre("ESTIMATE"));
      out.add("");
    }
  }

  /**
   * The template's opening row: document fields on the left, the shop block right-aligned on the
   * right. Rendered here by laying both columns out independently and pairing them line by line,
   * so a long address wrapping on the right cannot push the left column down.
   */
  private void appendPartiesRow(List<String> out, GenerateInvoiceRequest r, boolean estimate) {
    List<String> left = new ArrayList<>();
    left.add((estimate ? "Estimate No. " : "Invoice: ") + nullToEmpty(r.getInvoiceNo()));
    left.add("Date: " + join(" ", r.getInvoiceDate(), r.getInvoiceTime()));
    if (visible(r.getShowPaymentMethod()) && present(r.getPaymentMethod())) {
      left.add("Pay: " + r.getPaymentMethod());
    }

    List<String> right = new ArrayList<>();
    if (!visible(r.getShowSellerDetails()) || visible(r.getShowShopName())) {
      right.addAll(wrap(nullToEmpty(r.getShopName()).toUpperCase(), HALF));
    }
    if (visible(r.getShowSellerDetails())) {
      addIf(right, visible(r.getShowShopAddress()), r.getShopAddress(), null);
      addIf(right, visible(r.getShowShopPhone()), r.getShopPhone(), "Ph: ");
      addIf(right, visible(r.getShowShopEmail()), r.getShopEmail(), "Email: ");
      addIf(right, visible(r.getShowShopGstin()), r.getShopGstin(), "GSTIN: ");
      addIf(right, visible(r.getShowShopPan()), r.getShopPan(), "PAN: ");
      addIf(right, visible(r.getShowShopDlNo()), r.getShopDlNo(), "D.L. No.: ");
      addIf(right, visible(r.getShowShopFssai()), r.getShopFssai(), "FSSAI: ");
      addIf(right, visible(r.getShowShopTagline()), r.getShopTagline(), null);
    } else if (estimate) {
      // The template shows the address on an estimate even with seller details off.
      addIf(right, true, r.getShopAddress(), null);
    }

    for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
      String l = i < left.size() ? left.get(i) : "";
      String rr = i < right.size() ? right.get(i) : "";
      out.add(twoColumn(l, rr));
    }
    out.add("");
  }

  private void appendBuyer(List<String> out, GenerateInvoiceRequest r) {
    if (!visible(r.getShowBuyerDetails())) {
      return;
    }
    List<String> lines = new ArrayList<>();
    if (visible(r.getShowCustomerName())) {
      lines.add("Buyer: " + nullToEmpty(r.getCustomerName()));
    }
    addIf(lines, visible(r.getShowCustomerPhone()), r.getCustomerPhone(), "Ph: ");
    addIf(lines, visible(r.getShowCustomerEmail()), r.getCustomerEmail(), "Email: ");
    addIf(lines, visible(r.getShowCustomerAddress()), r.getCustomerAddress(), "Addr: ");
    addIf(lines, visible(r.getShowCustomerGstin()), r.getCustomerGstin(), "GSTIN: ");
    addIf(lines, visible(r.getShowCustomerPan()), r.getCustomerPan(), "PAN: ");
    addIf(lines, visible(r.getShowCustomerDlNo()), r.getCustomerDlNo(), "D.L. No.: ");
    if (lines.isEmpty()) {
      return;
    }
    out.addAll(lines);
    out.add("");
  }

  // ------------------------------------------------------------------ items

  private void appendItems(List<String> out, GenerateInvoiceRequest r) {
    List<Column> columns = buildColumns(r);
    int flex = flexWidth(columns);
    out.add(renderRow(columns, flex, Column::header));
    out.add(RULE);

    List<InvoiceItem> items = r.getItems() != null ? r.getItems() : List.of();
    for (InvoiceItem item : items) {
      out.add(renderRow(columns, flex, c -> c.value().apply(item)));
      // The template hangs HSN under the item name as a second line in the same cell.
      if (visible(r.getShowHsn()) && present(item.getHsn())) {
        out.add("  HSN: " + item.getHsn());
      }
    }
    out.add(RULE);
  }

  private List<Column> buildColumns(GenerateInvoiceRequest r) {
    List<Column> columns = new ArrayList<>();
    columns.add(new Column("Item", -1, false, i -> nullToEmpty(i.getName())));
    columns.add(new Column("Qty", 7, true, i -> quantity(i.getQuantity())));
    if (visible(r.getShowMrp())) {
      columns.add(new Column("MRP", 9, true, i -> money(i.getMaximumRetailPrice())));
    }
    columns.add(new Column("Rate", 9, true, i -> money(i.getPriceToRetail())));
    if (visible(r.getShowLineDiscount())) {
      columns.add(new Column("Disc", 8, true,
          i -> i.getDiscount() != null ? money(i.getDiscount()) : "0"));
    }
    columns.add(new Column("Amt", 10, true, i -> money(i.getTotalAmount())));
    return columns;
  }

  /** Width the flexible Item column absorbs. */
  private int flexWidth(List<Column> columns) {
    int fixed = columns.stream().filter(c -> c.width() > 0).mapToInt(Column::width).sum();
    return LINE_WIDTH - fixed - (columns.size() - 1);
  }

  private String renderRow(List<Column> columns, int flex, Function<Column, String> cell) {
    List<String> cells = new ArrayList<>();
    for (Column column : columns) {
      int width = column.width() > 0 ? column.width() : flex;
      cells.add(pad(cell.apply(column), width, column.rightAlign()));
    }
    return String.join(" ", cells);
  }

  // ----------------------------------------------------------------- totals

  /**
   * The template's totals, in its order and under its labels. Notably there is no round-off row
   * and no per-rate tax table: tax is a single line, shown only when tax details are on.
   */
  private void appendTotals(List<String> out, GenerateInvoiceRequest r) {
    out.add(totalRow("Total Amount", r.getSubTotal()));
    BigDecimal additional = r.getSaleAdditionalDiscountTotal();
    if (visible(r.getShowAdditionalDiscount()) && isPositive(additional)) {
      out.add(totalRow("Additional Discount", additional));
    }
    if (visible(r.getShowTaxDetails())) {
      out.add(totalRow("Tax", r.getTaxTotal()));
    }
    out.add(totalRow("Net Amount", r.getGrandTotal()));
    if (visible(r.getShowAmountSaved())) {
      BigDecimal saved = r.getTotalAmountSaved() != null ? r.getTotalAmountSaved() : BigDecimal.ZERO;
      out.add(totalRow("AMOUNT SAVED", saved));
    }
  }

  private void appendFooter(List<String> out, GenerateInvoiceRequest r) {
    if (visible(r.getShowAmountInWords()) && present(r.getAmountInWords())) {
      out.add("");
      for (String line : wrap(r.getAmountInWords(), LINE_WIDTH)) {
        out.add(centre(line));
      }
    }
    if (present(r.getFooterNote())) {
      out.add("");
      for (String line : wrap(r.getFooterNote(), LINE_WIDTH)) {
        out.add(centre(line));
      }
    }
  }

  // ---------------------------------------------------------------- helpers

  private static boolean isEstimate(GenerateInvoiceRequest r) {
    return "ESTIMATE".equalsIgnoreCase(r.getDocumentType())
        || "BASIC".equalsIgnoreCase(r.getBillingMode());
  }

  private static String totalRow(String label, BigDecimal amount) {
    // Label left, amount hard against the right margin, as the template's totals table does.
    String value = money(amount);
    int gap = Math.max(1, LINE_WIDTH - label.length() - value.length());
    return label + " ".repeat(gap) + value;
  }

  /** Adds "prefix + value" when the flag is on and the value is present, as the template gates. */
  private static void addIf(List<String> lines, boolean shown, String value, String prefix) {
    if (!shown || !present(value)) {
      return;
    }
    lines.addAll(wrap((prefix == null ? "" : prefix) + value.trim(), HALF));
  }

  /** Money with two decimals. A null value renders blank, never 0.00. */
  private static String money(BigDecimal value) {
    return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /**
   * Quantity as the preview shows it: a whole number prints without decimals, so 5 rather than
   * 5.00, and a fractional quantity keeps only the places it actually uses.
   */
  private static String quantity(BigDecimal value) {
    if (value == null) {
      return "";
    }
    BigDecimal trimmed = value.stripTrailingZeros();
    return trimmed.scale() <= 0 ? trimmed.toBigInteger().toString() : trimmed.toPlainString();
  }

  private static String pad(String raw, int width, boolean rightAlign) {
    String value = clean(raw);
    if (value.length() > width) {
      return value.substring(0, width);
    }
    String filler = " ".repeat(width - value.length());
    return rightAlign ? filler + value : value + filler;
  }

  private static String centre(String raw) {
    String value = clean(raw);
    if (value.length() >= LINE_WIDTH) {
      return value.substring(0, LINE_WIDTH);
    }
    return " ".repeat((LINE_WIDTH - value.length()) / 2) + value;
  }

  private static String twoColumn(String left, String right) {
    String l = clean(left);
    String r = clean(right);
    if (l.length() > HALF) {
      l = l.substring(0, HALF);
    }
    if (r.length() > LINE_WIDTH - HALF) {
      r = r.substring(0, LINE_WIDTH - HALF);
    }
    int gap = Math.max(1, LINE_WIDTH - l.length() - r.length());
    String line = l + " ".repeat(gap) + r;
    return line.length() > LINE_WIDTH ? line.substring(0, LINE_WIDTH) : line;
  }

  /** Greedy word wrap. Words longer than the width are hard-split. */
  private static List<String> wrap(String raw, int width) {
    List<String> lines = new ArrayList<>();
    String value = clean(raw).trim();
    if (value.isEmpty()) {
      return lines;
    }
    StringBuilder current = new StringBuilder();
    for (String token : value.split("\\s+")) {
      String word = token;
      while (word.length() > width) {
        if (current.length() > 0) {
          lines.add(current.toString());
          current.setLength(0);
        }
        lines.add(word.substring(0, width));
        word = word.substring(width);
      }
      if (current.length() == 0) {
        current.append(word);
      } else if (current.length() + 1 + word.length() <= width) {
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

  /**
   * Neutralises control characters and folds anything outside printable ASCII to '?'.
   *
   * <p>A CR or LF in a product or customer name — plausible from a pasted spreadsheet — would
   * otherwise split one row into two physical lines and destroy every column below it. The fold
   * keeps this renderer's idea of a column identical to the encoder's: ESC/P text mode is
   * single-byte, and Java counts UTF-16 units where the encoder counts runes, so the two
   * disagree on astral characters unless both reduce to one byte each.
   */
  private static String clean(String raw) {
    if (raw == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(raw.length());
    raw.codePoints().forEach(cp -> {
      if (cp == '\t' || cp == '\n' || cp == '\r') {
        sb.append(' ');
      } else if (cp >= 0x20 && cp <= 0x7E) {
        sb.append((char) cp);
      } else {
        sb.append('?');
      }
    });
    return sb.toString();
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
