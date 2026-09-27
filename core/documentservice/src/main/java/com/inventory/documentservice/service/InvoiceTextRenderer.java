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

  /**
   * The tax invoice prints condensed, at fifteen characters to the inch.
   *
   * <p>Its thirteen columns need eighty-five characters before the product name gets one, so at
   * ten CPI the grid cannot be drawn and the least load-bearing columns were being dropped. Eight
   * inches at fifteen CPI is a hundred and twenty, which fits every column with room for a
   * readable name.
   *
   * <p>A hundred and eight characters is exactly nine inches at twelve CPI - the coarsest pitch this grid
   * fits, and the one the trade bills it is compared against are printed in. It was a hundred
   * and thirty-five, which only fits at condensed 17.1 CPI, and the type came out visibly
   * smaller than the bill beside it for no gain: the same fourteen columns fit in a hundred and
   * six once each is given the width it actually needs rather than a generous one.
   *
   * <p>The estimate stays at eighty, where it fits at ten CPI and is larger still.
   */
  public static final int TAX_LINE_WIDTH = 137;

  /** What the tax bill calls itself, above the shop's own masthead. */
  private static final String TAX_TITLE = "TAX INVOICE";

  /**
   * The grid's own characters, as the trade bill draws them: a bar between columns, a cross
   * where a bar meets a rule.
   *
   * <p>The colon that stood between columns before read as punctuation inside the values - a
   * batch number and the rate beside it ran together - where a bar reads as the edge of a
   * column and nothing else.
   */
  private static final String GRID_BAR = "|";

  private static final String GRID_CORNER = "+";

  /**
   * Columns kept clear at the right of the totals block.
   *
   * <p>NET AMOUNT is set double width, and a double-width glyph that would end flush against
   * the right margin does not fit there: the printer wrapped it, so a lone huge "0" printed on
   * the line below and the bill read "210.0". Ending the block short of the margin leaves that
   * glyph somewhere to land. The whole block is pulled in, not just the net row, so the figures
   * stay in one column.
   */
  private static final int TAX_TOTALS_RIGHT_MARGIN = 2;

  /** Half the width, for the two-column header the template lays out as a table row. */
  private static final int HALF = LINE_WIDTH / 2;

  private static final String RULE = "-".repeat(LINE_WIDTH);

  /** Closes the item grid and separates it from the totals, as the reference bill does. */
  private static final String DOUBLE_RULE = "=".repeat(LINE_WIDTH);

  /** Widths of the tax invoice's three party columns: buyer, its tax ids, the document. */
  private static final int TAX_LEFT = 32;

  private static final int TAX_MIDDLE = 24;

  /** The totals block on a tax invoice: label then figure, right against the margin. */
  private static final int TAX_TOTAL_LABEL = 20;

  private static final int TAX_TOTAL_VALUE = 12;

  /** Least load-bearing first: what gets dropped so the product name stays readable. */
  /**
   * Which columns give way when the page is too narrow, in order.
   *
   * <p>SGST and CGST are not on the list. They used to head it, so the first thing a narrow
   * page dropped was the tax the invoice exists to state - a document that is not a tax invoice
   * without them. Presentation goes first, then the fields a buyer can look up elsewhere.
   */
  private static final List<String> TAX_COLUMN_SACRIFICE_ORDER =
      List.of("MFG/MKD.", "PACK.", "SCHEME", "Exp.Dt", "M.R.P.", "HSN/SAC");

  /** A product name narrower than this is not worth printing. */
  // Twenty, which condensed leaves room for. The reference bill gives its product column
  // twenty-eight.
  private static final int TAX_NAME_MIN = 20;

  /**
   * ESC/P emphasised mode on and off. The printer draws these lines twice, which is the only
   * bold a text-mode printer has; the preview turns the same pair into markup. Centring happens
   * before they are added, so they never count towards the width.
   */
  public static final String BOLD_ON = "\u001B\u0045";

  public static final String BOLD_OFF = "\u001B\u0046";

  private static String bold(String line) {
    return BOLD_ON + line + BOLD_OFF;
  }

  /**
   * ESC/P condensed pitch on and off - 17.1 characters to the inch instead of 10. The smallest
   * type the printer has, which is what a maker's credit line should be set in.
   */
  public static final String CONDENSED_ON = "\u000F";

  public static final String CONDENSED_OFF = "\u0012";

  private static String condensed(String line) {
    return CONDENSED_ON + line + CONDENSED_OFF;
  }

  /**
   * ESC/P double-width on and off. Every character between them is drawn twice as wide, so it
   * occupies two printing columns rather than one - which is what the eye reads as a bigger
   * character on a printer that has one type size.
   */
  public static final String DOUBLE_ON = "\u000E";

  public static final String DOUBLE_OFF = "\u0014";

  private static String doubleWide(String line) {
    return DOUBLE_ON + line + DOUBLE_OFF;
  }

  /**
   * Centres text that will print at double width.
   *
   * <p>The padding is computed against twice the text's length, because that is the room it
   * takes on paper. Centring it by its character count would sit it a quarter of the page to
   * the left of where it lands.
   */
  /**
   * Opens a space between every character, so a name reads as a masthead rather than a word.
   *
   * <p>The gap between words has to grow with it. Spacing the letters alone leaves the same
   * single space between "KUBER" and "PHARMA" as between its letters, and the whole name reads
   * as one word.
   */
  private static String letterSpaced(String text) {
    List<String> words = new ArrayList<>();
    for (String word : text.trim().split("\\s+")) {
      if (word.isEmpty()) {
        continue;
      }
      StringBuilder spaced = new StringBuilder(word.length() * 2);
      for (int i = 0; i < word.length(); i++) {
        if (i > 0) {
          spaced.append(' ');
        }
        spaced.append(word.charAt(i));
      }
      words.add(spaced.toString());
    }
    return String.join("   ", words);
  }

  private static String centreWide(String text, int width) {
    int printed = text.length() * 2;
    int left = Math.max(0, (width - printed) / 2);
    return " ".repeat(left) + doubleWide(text);
  }

  /** Who made the bill. Tax invoices only: an estimate is a quote, not a document of record. */
  private static final String MAKER_CREDIT = "stockkart.co.in | for all kind of shops | call: 9828606899";

  /** The warranty wording a drug bill carries, as invoice.html prints it. */
  private static final List<String> DRUG_ACT_TERMS = List.of(
      "1. On the strength of Warranty obtained by us We hereby give this Warranty that the goods "
          + "sold by us do not contravene the provisions of the Drugs Act 1940.",
      "2. Price charged excess due to oversight may be referred to us for rectification.");

  /**
   * The item columns, mirroring the template's widths (42/10/12/12/12/12 of the table). MRP and
   * Disc are conditional there, so they are conditional here; Item takes whatever is left.
   */
  private record Column(String header, int width, boolean rightAlign,
      Function<InvoiceItem, String> value) {}

  public String render(GenerateInvoiceRequest request) {
    List<String> out = new ArrayList<>();
    boolean estimate = isEstimate(request);

    if (!estimate) {
      // A tax invoice follows the trade layout: a centred shop masthead, the buyer and the
      // document facing each other, then one line per item carrying every column. The estimate
      // keeps the plainer two-column form below.
      appendTaxInvoice(out, request);
      return finish(out);
    }

    appendMasthead(out, request, "ESTIMATE", LINE_WIDTH);
    appendEstimateParties(out, request);
    appendItems(out, request);
    appendTotals(out, request);
    appendFooter(out, request);

    return finish(out);
  }

  /** Trailing padding is invisible on screen and wasted carriage travel on paper; trim it. */
  private static String finish(List<String> out) {
    List<String> trimmed = new ArrayList<>(out.size());
    for (String line : out) {
      trimmed.add(line.stripTrailing());
    }
    return String.join("\n", trimmed) + "\n";
  }

  /**
   * Buyer down the left, the estimate's own details opposite. They used to stack, which cost
   * four lines and a blank on a page where vertical space is the scarce thing.
   *
   * The customer's name carries no "Buyer:" label: it sits at the head of its own block, and
   * the line beneath it already reads "Ph:", so nothing is ambiguous without it.
   */
  private void appendEstimateParties(List<String> out, GenerateInvoiceRequest r) {
    List<String> right = new ArrayList<>();
    right.add((isEstimate(r) ? "Estimate No. " : "Invoice: ") + nullToEmpty(r.getInvoiceNo()));
    right.add("Date: " + join(" ", r.getInvoiceDate(), r.getInvoiceTime()));
    if (visible(r.getShowPaymentMethod()) && present(r.getPaymentMethod())) {
      right.add("Pay: " + r.getPaymentMethod());
    }

    List<String> left = new ArrayList<>();
    if (visible(r.getShowBuyerDetails())) {
      if (visible(r.getShowCustomerName()) && present(r.getCustomerName())) {
        left.addAll(wrap(r.getCustomerName().trim(), HALF));
      }
      addIf(left, visible(r.getShowCustomerPhone()), r.getCustomerPhone(), "Ph: ");
      addIf(left, visible(r.getShowCustomerEmail()), r.getCustomerEmail(), "Email: ");
      addIf(left, visible(r.getShowCustomerAddress()), r.getCustomerAddress(), "Addr: ");
      addIf(left, visible(r.getShowCustomerGstin()), r.getCustomerGstin(), "GSTIN: ");
      addIf(left, visible(r.getShowCustomerPan()), r.getCustomerPan(), "PAN: ");
      addIf(left, visible(r.getShowCustomerDlNo()), r.getCustomerDlNo(), "D.L. No.: ");
    }

    for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
      out.add(twoColumn(i < left.size() ? left.get(i) : "", i < right.size() ? right.get(i) : ""));
    }
    out.add("");
  }

  // ------------------------------------------------------------ tax invoice

  private void appendTaxInvoice(List<String> out, GenerateInvoiceRequest r) {
    // The trade bill names itself above the shop, and the shop asked for the same. It was
    // dropped once on the argument that GSTIN, HSN and a tax table said it well enough; the
    // counter reads the words, not the columns.
    //
    // Letter-spaced and emphasised, but not set double width, and so written here rather than
    // handed to appendMasthead like the estimate's title. The shop's name on the line below is
    // double width; with the title double width too the printer put the two on top of each
    // other, and the masthead read "K U B EAR INPOMCA R M A". One double-width line per
    // masthead is the shape that has printed correctly all along, and spacing the letters is
    // what makes a title read as one without it.
    out.add(BOLD_ON + centre(letterSpaced(TAX_TITLE), TAX_LINE_WIDTH) + BOLD_OFF);
    appendMasthead(out, r, null, TAX_LINE_WIDTH);
    appendTaxParties(out, r);
    appendTaxItems(out, r);
    appendTaxTotals(out, r);
    appendTaxFooter(out, r);
  }

  /** Centred shop block: title, name, address, licences, contact, then the stockist line. */
  private void appendMasthead(
      List<String> out, GenerateInvoiceRequest r, String title, int width) {
    // The title is the one thing read from across a counter, so it prints at double width.
    // A null title means the document names itself well enough without one: the tax invoice
    // carries GSTIN, HSN and a tax table, and the word above them said nothing the shop's own
    // masthead did not.
    if (title != null) {
      out.add(BOLD_ON + centreWide(title, width) + BOLD_OFF);
    }
    if (visible(r.getShowSellerDetails())) {
      if (visible(r.getShowShopName())) {
        // Letter-spaced as well as double-width. A text-mode printer has one type size, so the
        // only ways to make a name read larger are to widen its characters and to open the
        // space between them; the trade bills set their masthead both ways.
        out.add(BOLD_ON
            + centreWide(letterSpaced(nullToEmpty(r.getShopName()).toUpperCase()), width)
            + BOLD_OFF);
        // The name needs air under it, or it reads as the first line of the address.
        out.add("");
      }
      addCentred(out, visible(r.getShowShopAddress()), r.getShopAddress(), null, width);

      List<String> licences = new ArrayList<>();
      addPart(licences, visible(r.getShowShopDlNo()), r.getShopDlNo(), "D.L.No.: ");
      addPart(licences, visible(r.getShowShopFssai()), r.getShopFssai(), "FSSAI: ");
      addCentred(out, true, String.join("  ", licences), null, width);

      addCentred(out, visible(r.getShowShopGstin()), r.getShopGstin(), "GST NO.: ", width);
      addCentred(out, visible(r.getShowShopPan()), r.getShopPan(), "PAN: ", width);

      List<String> contact = new ArrayList<>();
      addPart(contact, visible(r.getShowShopPhone()), r.getShopPhone(), "Phone: ");
      addPart(contact, visible(r.getShowShopEmail()), r.getShopEmail(), "E-Mail: ");
      addCentred(out, true, String.join("  ", contact), null, width);

      if (visible(r.getShowShopTagline()) && present(r.getShopTagline())) {
        out.add(rule(width));
        for (String line : wrap(r.getShopTagline(), width)) {
          out.add(line);
        }
      }
    }
    out.add(rule(width));
  }

  /** Buyer on the left, its tax identifiers in the middle, the document's own details right. */
  private void appendTaxParties(List<String> out, GenerateInvoiceRequest r) {
    List<String> left = new ArrayList<>();
    List<String> middle = new ArrayList<>();
    if (visible(r.getShowBuyerDetails())) {
      if (visible(r.getShowCustomerName())) {
        left.addAll(wrap("M/s. " + nullToEmpty(r.getCustomerName()), TAX_LEFT));
      }
      addWrapped(left, visible(r.getShowCustomerAddress()), r.getCustomerAddress(), null, TAX_LEFT);
      addWrapped(left, visible(r.getShowCustomerPhone()), r.getCustomerPhone(), "Ph: ", TAX_LEFT);
      // The place of supply, which decides whether the tax splits into SGST and CGST or falls
      // wholly to IGST, so a trade bill states it beside the buyer it applies to.
      addWrapped(left, true, r.getPlaceOfSupply(), "State Code: ", TAX_LEFT);
      addWrapped(middle, visible(r.getShowCustomerDlNo()), r.getCustomerDlNo(),
          "D.L.NO.: ", TAX_MIDDLE);
      addWrapped(middle, visible(r.getShowCustomerGstin()), r.getCustomerGstin(),
          "GSTIN: ", TAX_MIDDLE);
      addWrapped(middle, visible(r.getShowCustomerPan()), r.getCustomerPan(),
          "PAN No.: ", TAX_MIDDLE);
    }

    List<String> right = new ArrayList<>();
    right.add("Inv.No.: " + nullToEmpty(r.getInvoiceNo()));
    if (present(r.getInvoiceDate())) {
      right.add("DATE   : " + r.getInvoiceDate());
    }
    if (present(r.getInvoiceTime())) {
      right.add("TIME   : " + r.getInvoiceTime());
    }
    if (visible(r.getShowPaymentMethod()) && present(r.getPaymentMethod())) {
      right.add("PAY    : " + r.getPaymentMethod());
    }

    // The document column is pushed as a block to the right margin - each line indented by the
    // same amount - so its labels stay in one line with each other while the column ends flush
    // rather than short of the rules.
    int rightWidth = TAX_LINE_WIDTH - TAX_LEFT - TAX_MIDDLE - 2;
    int longest = right.stream().mapToInt(String::length).max().orElse(0);
    int indent = Math.max(0, rightWidth - longest);

    int rows = Math.max(left.size(), Math.max(middle.size(), right.size()));
    for (int i = 0; i < rows; i++) {
      String l = i < left.size() ? left.get(i) : "";
      String m = i < middle.size() ? middle.get(i) : "";
      String rr = i < right.size() ? " ".repeat(indent) + right.get(i) : "";
      out.add(pad(l, TAX_LEFT, false) + " " + pad(m, TAX_MIDDLE, false) + " "
          + pad(rr, rightWidth, false));
    }
    // No rule closes this block: the grid below opens with one of its own, and two full-width
    // rules on consecutive lines read as a mistake and cost a line the page cannot spare.
  }

  /**
   * One line per item, every column on it. The columns are sized to the full width so a name
   * that would not fit is truncated rather than wrapped: a second line under an item breaks the
   * grid a trade bill is read down.
   */
  private void appendTaxItems(List<String> out, GenerateInvoiceRequest r) {
    List<Column> columns = buildTaxColumns(r);
    int flex = taxFlexWidth(columns);
    String columnRule = columnRule(columns, flex);

    out.add(columnRule);
    out.add(borderedRow(columns, flex, Column::header));
    out.add(columnRule);

    List<InvoiceItem> items = r.getItems() != null ? r.getItems() : List.of();
    for (InvoiceItem item : items) {
      out.add(borderedRow(columns, flex, c -> c.value().apply(item)));
    }
    // One rule closes the grid rather than one per row. A rule between every item doubled the
    // height of the grid, and the trade bill it is compared against rules the head and the
    // foot only.
    out.add(columnRule);
    out.add("NO OF ITEMS : " + items.size());
  }

  /**
   * The flexible column's share once the bars are paid for.
   *
   * <p>A bordered grid carries one bar between each pair of columns and one at each end, so it
   * spends {@code columns + 1} characters on structure where the old colon-separated grid spent
   * {@code columns - 1}. The product name absorbs the difference, so the grid still ends exactly
   * on the right margin.
   */
  private int taxFlexWidth(List<Column> columns) {
    int fixed = columns.stream().filter(c -> c.width() > 0).mapToInt(Column::width).sum();
    return TAX_LINE_WIDTH - fixed - (columns.size() + 1);
  }

  /**
   * The rule that closes a bordered grid: dashes across each column, a cross at every bar.
   *
   * <p>Drawn from the same widths as the rows themselves, so the crosses land on the bars
   * rather than near them - a plain row of dashes leaves the eye to guess where a column ends,
   * which is the thing a trade bill's grid exists to answer.
   */
  private String columnRule(List<Column> columns, int flex) {
    StringBuilder out = new StringBuilder(TAX_LINE_WIDTH);
    out.append(GRID_CORNER);
    for (Column column : columns) {
      out.append("-".repeat(column.width() > 0 ? column.width() : flex)).append(GRID_CORNER);
    }
    return out.toString();
  }

  /** One grid row, every column boxed in by bars. */
  private String borderedRow(List<Column> columns, int flex, Function<Column, String> cell) {
    return GRID_BAR + renderRow(columns, flex, cell, GRID_BAR) + GRID_BAR;
  }

  /**
   * The columns that fit. Eleven of them plus a product name do not go into eighty characters,
   * so a column that no item on this bill fills is left out, and if the name is still too narrow
   * to read, the least load-bearing columns are dropped in turn. What survives is always a
   * single line per item: a name wrapped onto a second line breaks the grid the bill is read
   * down. A wider carriage (condensed print) would keep every column; this is the honest fit at
   * eighty.
   */
  private List<Column> buildTaxColumns(GenerateInvoiceRequest r) {
    List<InvoiceItem> items = r.getItems() != null ? r.getItems() : List.of();
    List<Column> columns = new ArrayList<>();
    // Headings and order as the trade bill sets them. There is no PACK column: the pack size
    // is not a field of its own here, it is written into the product name.
    columns.add(new Column("QTY.", 8, true, i -> quantity(i.getQuantity())));
    columns.add(new Column("PACK.", 6, false, i -> nullToEmpty(i.getPack())));
    columns.add(new Column("PRODUCTS", -1, false, i -> nullToEmpty(i.getName())));
    addTaxColumn(columns, items, visible(r.getShowHsn()),
        new Column("HSN/SAC", 8, false, i -> nullToEmpty(i.getHsn())));
    addTaxColumn(columns, items, visible(r.getShowMfg()),
        new Column("MFG/MKD.", 8, false, i -> nullToEmpty(i.getCompanyName())));
    addTaxColumn(columns, items, visible(r.getShowExpiry()),
        new Column("Exp.Dt", 6, false, i -> nullToEmpty(i.getExpiryDate())));
    addTaxColumn(columns, items, visible(r.getShowBatch()),
        new Column("BATCH No.", 12, false, i -> nullToEmpty(i.getBatchNo())));
    addTaxColumn(columns, items, visible(r.getShowMrp()),
        new Column("M.R.P.", 9, true, i -> money(i.getMaximumRetailPrice())));
    columns.add(new Column("RATE", 8, true, i -> money(i.getPriceToRetail())));
    addTaxColumn(columns, items, visible(r.getShowScheme()),
        new Column("SCHEME", 6, true, InvoiceTextRenderer::schemeLabel));
    addTaxColumn(columns, items, visible(r.getShowLineDiscount()),
        new Column("DIS%", 5, true, i ->
            i.getSaleAdditionalDiscount() != null ? money(i.getSaleAdditionalDiscount()) : ""));
    addTaxColumn(columns, items, visible(r.getShowTaxDetails()),
        new Column("SGST", 4, true, i -> rate(i.getSgst())));
    addTaxColumn(columns, items, visible(r.getShowTaxDetails()),
        new Column("CGST", 4, true, i -> rate(i.getCgst())));
    columns.add(new Column("AMOUNT", 10, true, i -> money(i.getTotalAmount())));

    for (String sacrifice : TAX_COLUMN_SACRIFICE_ORDER) {
      if (taxFlexWidth(columns) >= TAX_NAME_MIN) {
        break;
      }
      columns.removeIf(c -> c.header().equals(sacrifice));
    }
    return columns;
  }

  /**
   * Keeps a column when its switch is on.
   *
   * <p>The switch decides, not the data. Dropping a column because no item on this particular
   * bill filled it meant the grid changed shape from one bill to the next, and a shop that had
   * turned Scheme on saw no Scheme column at all on a bill where nothing carried one - which
   * reads as the setting being ignored rather than as an empty column.
   */
  private static void addTaxColumn(
      List<Column> columns, List<InvoiceItem> items, boolean shown, Column column) {
    if (shown) {
      columns.add(column);
    }
  }

  /** A per-line tax rate as the bill states it: 2.5, not 2.50, and blank when unset. */
  private static String rate(String raw) {
    if (!present(raw)) {
      return "";
    }
    try {
      return quantity(new BigDecimal(raw.trim()));
    } catch (NumberFormatException e) {
      return raw.trim();
    }
  }

  /** The scheme as the bill states it: a percentage, a pay-for/free pair, or nothing. */
  private static String schemeLabel(InvoiceItem i) {
    if (i.getSchemePercentage() != null) {
      return quantity(i.getSchemePercentage()) + "%";
    }
    if (i.getSchemePayFor() != null && i.getSchemeFree() != null) {
      return i.getSchemePayFor() + "+" + i.getSchemeFree();
    }
    return i.getScheme() != null ? String.valueOf(i.getScheme()) : "";
  }

  /**
   * The totals column a trade bill carries: gross, the discount taken off it, each tax added
   * back, the rounding, then the net. Sits against the right margin under the item grid.
   */
  /**
   * The closing figure of the tax bill, emphasised and at double width.
   *
   * <p>Measured against twice the value's length, since each of its characters occupies two
   * printing columns.
   */
  private static String taxNetRow(String label, BigDecimal amount) {
    String value = money(amount);
    // The label keeps the width the other rows give it, so the column of labels stays straight;
    // only the figure is set wide, and it still ends on the right margin.
    int printed = TAX_TOTAL_LABEL + value.length() * 2 + TAX_TOTALS_RIGHT_MARGIN;
    return " ".repeat(Math.max(0, TAX_LINE_WIDTH - printed))
        + BOLD_ON + pad(label, TAX_TOTAL_LABEL, false) + doubleWide(value) + BOLD_OFF;
  }

  /** "Add SGST 2.5 %", so the rate charged is stated beside the amount it produced. */
  private static String taxLabel(String label, BigDecimal percent) {
    return percent == null ? label : label + " " + quantity(percent) + " %";
  }

  private void appendTaxTotals(List<String> out, GenerateInvoiceRequest r) {
    out.add(taxTotalRow("TOTAL AMOUNT", r.getSubTotal()));
    if (visible(r.getShowAdditionalDiscount()) && isPositive(r.getSaleAdditionalDiscountTotal())) {
      out.add(taxTotalRow("Less Discount", r.getSaleAdditionalDiscountTotal()));
    }
    if (visible(r.getShowTaxDetails())) {
      if (isPositive(r.getSgstAmount())) {
        out.add(taxTotalRow(taxLabel("Add SGST", r.getSgstPercent()), r.getSgstAmount()));
      }
      if (isPositive(r.getCgstAmount())) {
        out.add(taxTotalRow(taxLabel("Add CGST", r.getCgstPercent()), r.getCgstAmount()));
      }
    }
    if (isPositive(r.getRoundOff())) {
      out.add(taxTotalRow("Less Roundoff", r.getRoundOff()));
    }
    // Savings reads opposite the totals block rather than as one more line inside it, where it
    // competed with NET AMOUNT for the eye. The left of this row is empty on a trade bill.
    String netRow = taxNetRow("NET AMOUNT", r.getGrandTotal());
    if (visible(r.getShowAmountSaved()) && isPositive(r.getTotalAmountSaved())) {
      out.add(overlayLeft(netRow, "AMOUNT SAVED  " + money(r.getTotalAmountSaved())));
    } else {
      out.add(netRow);
    }
  }

  /**
   * Writes text into the left of a line that is already laid out on the right.
   *
   * <p>The totals block is built as full-width strings padded from the left, so there is no
   * column to write into - the space is real characters. Overwrite them in place, and leave the
   * line untouched if the two would collide.
   */
  private static String overlayLeft(String line, String left) {
    String text = clean(left);
    if (text.isEmpty() || text.length() + 2 > line.length()) {
      return line;
    }
    String tail = line.substring(text.length());
    // Only safe where the row's own text has not reached: anything else would be overwritten.
    if (!tail.isBlank() && !line.substring(0, text.length() + 2).isBlank()) {
      return line;
    }
    return text + line.substring(text.length());
  }

  /** Label and figure kept together in a block on the right, not spread across the page. */
  private static String taxTotalRow(String label, BigDecimal amount) {
    String value = money(amount);
    String block = pad(label, TAX_TOTAL_LABEL, false) + pad(value, TAX_TOTAL_VALUE, true);
    return " ".repeat(Math.max(0, TAX_LINE_WIDTH - block.length() - TAX_TOTALS_RIGHT_MARGIN))
        + block;
  }

  /**
   * The one-line GST working: what was taxed, at what rates, and what each half came to.
   *
   * <p>A trade bill states this so the figures in the totals can be checked without a
   * calculator, and so the return can be filled from the bill itself.
   */
  private void appendGstSummary(List<String> out, GenerateInvoiceRequest r) {
    if (!visible(r.getShowTaxDetails())
        || !isPositive(r.getSgstAmount()) && !isPositive(r.getCgstAmount())) {
      return;
    }
    BigDecimal taxable = r.getSubTotal() == null ? BigDecimal.ZERO : r.getSubTotal();
    if (isPositive(r.getSaleAdditionalDiscountTotal())) {
      taxable = taxable.subtract(r.getSaleAdditionalDiscountTotal());
    }
    out.add("GST=" + money(taxable)
        + "*" + quantity(r.getSgstPercent()) + "*" + quantity(r.getCgstPercent()) + "%="
        + money(r.getSgstAmount()) + "SGST+" + money(r.getCgstAmount()) + "CGST.");
  }

  private void appendTaxFooter(List<String> out, GenerateInvoiceRequest r) {
    appendGstSummary(out, r);
    if (visible(r.getShowAmountInWords()) && present(r.getAmountInWords())) {
      for (String line : wrap("Rs. " + r.getAmountInWords(), TAX_LINE_WIDTH)) {
        out.add(line);
      }
    }
    out.add(rule(TAX_LINE_WIDTH));
    for (String term : DRUG_ACT_TERMS) {
      out.addAll(wrap(term, TAX_LINE_WIDTH));
    }
    // Named from the place of supply rather than assumed: a bill that claims the wrong
    // jurisdiction is worse than one that claims none.
    if (present(r.getPlaceOfSupply())) {
      out.addAll(wrap("3. All subject to " + r.getPlaceOfSupply().trim().toUpperCase()
          + " Jurisdiction only.", TAX_LINE_WIDTH));
    }
    if (present(r.getFooterNote())) {
      for (String line : wrap(r.getFooterNote(), TAX_LINE_WIDTH)) {
        out.add(line);
      }
    }
    if (visible(r.getShowSignatures())) {
      out.add("");
      String shop = visible(r.getShowSellerDetails()) && present(r.getShopName())
          ? "For, " + r.getShopName().toUpperCase()
          : "";
      out.add(twoColumn("E.& O.E.", shop, TAX_LINE_WIDTH));
      out.add("");
      out.add("");
      out.add(twoColumn("Received By", "Authorised Signatory", TAX_LINE_WIDTH));
    }
    // Plain text, bottom left. The condensed markers are SI and DC2, and the print
    // bridge folds every byte outside printable ASCII to '?', so wrapping this line
    // in them printed "?stockkart...?" on paper rather than shrinking it.
    out.add(MAKER_CREDIT);
  }

  /** Centres "prefix + value" when shown and present, wrapping to the page. */
  private static void addCentred(
      List<String> out, boolean shown, String value, String prefix, int width) {
    if (!shown || !present(value)) {
      return;
    }
    for (String line : wrap((prefix == null ? "" : prefix) + value.trim(), width)) {
      out.add(centre(line, width));
    }
  }

  private static void addPart(List<String> parts, boolean shown, String value, String prefix) {
    if (shown && present(value)) {
      parts.add(prefix + value.trim());
    }
  }

  private static void addWrapped(
      List<String> lines, boolean shown, String value, String prefix, int width) {
    if (!shown || !present(value)) {
      return;
    }
    lines.addAll(wrap((prefix == null ? "" : prefix) + value.trim(), width));
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
  private void appendPartiesRow(List<String> out, GenerateInvoiceRequest r) {
    List<String> left = new ArrayList<>();
    left.add((isEstimate(r) ? "Estimate No. " : "Invoice: ") + nullToEmpty(r.getInvoiceNo()));
    left.add("Date: " + join(" ", r.getInvoiceDate(), r.getInvoiceTime()));
    if (visible(r.getShowPaymentMethod()) && present(r.getPaymentMethod())) {
      left.add("Pay: " + r.getPaymentMethod());
    }

    // Every seller line sits behind the seller-details switch, and each line behind its own,
    // exactly as invoice.html gates them. Printing the name when the switch was off - and the
    // address too on an estimate - made the switch read backwards: turning it on emptied the
    // block, because the individual lines underneath were still off.
    List<String> right = new ArrayList<>();
    if (visible(r.getShowSellerDetails())) {
      if (visible(r.getShowShopName())) {
        right.addAll(wrap(nullToEmpty(r.getShopName()).toUpperCase(), HALF));
      }
      addIf(right, visible(r.getShowShopAddress()), r.getShopAddress(), null);
      addIf(right, visible(r.getShowShopPhone()), r.getShopPhone(), "Ph: ");
      addIf(right, visible(r.getShowShopEmail()), r.getShopEmail(), "Email: ");
      addIf(right, visible(r.getShowShopGstin()), r.getShopGstin(), "GSTIN: ");
      addIf(right, visible(r.getShowShopPan()), r.getShopPan(), "PAN: ");
      addIf(right, visible(r.getShowShopDlNo()), r.getShopDlNo(), "D.L. No.: ");
      addIf(right, visible(r.getShowShopFssai()), r.getShopFssai(), "FSSAI: ");
      addIf(right, visible(r.getShowShopTagline()), r.getShopTagline(), null);
    }

    for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
      String l = i < left.size() ? left.get(i) : "";
      String rr = i < right.size() ? right.get(i) : "";
      out.add(twoColumn(l, rr));
    }
    out.add("");
  }

  // ------------------------------------------------------------------ items

  private void appendItems(List<String> out, GenerateInvoiceRequest r) {
    List<Column> columns = buildColumns(r);
    int flex = flexWidth(columns);
    out.add(renderRow(columns, flex, Column::header, ":"));
    out.add(RULE);

    List<InvoiceItem> items = r.getItems() != null ? r.getItems() : List.of();
    for (InvoiceItem item : items) {
      out.add(renderRow(columns, flex, c -> c.value().apply(item), ":"));
      if (visible(r.getShowHsn()) && present(item.getHsn())) {
        out.add("  HSN: " + item.getHsn());
      }
      // A rule under each item, so one line cannot be read across into the next.
      out.add(RULE);
    }
    if (items.isEmpty()) {
      out.add(RULE);
    }
    // What the counter checks the bag against before handing it over.
    out.add("NO OF ITEMS : " + items.size());
    out.add(DOUBLE_RULE);
  }

  private List<Column> buildColumns(GenerateInvoiceRequest r) {
    List<Column> columns = new ArrayList<>();
    columns.add(new Column("Item", -1, false, i -> nullToEmpty(i.getName())));
    columns.add(new Column("Qty", 7, true, i -> quantity(i.getQuantity())));
    if (visible(r.getShowMrp())) {
      columns.add(new Column("MRP", 9, true, i -> money(i.getMaximumRetailPrice())));
    }
    columns.add(new Column("Rate", 9, true, i -> money(i.getPriceToRetail())));
    if (visible(r.getShowScheme())) {
      columns.add(new Column("Sch", 5, true, InvoiceTextRenderer::schemeLabel));
    }
    if (visible(r.getShowLineDiscount())) {
      // The rate the operator applied, not its rupee value. getDiscount() holds
      // (MRP - rate) x quantity, so a line at 206.25 sold for 157.16 printed 49.09 under a
      // heading that reads as a percentage - the same figure the totals already carry.
      columns.add(new Column("Disc%", 6, true,
          i -> i.getSaleAdditionalDiscount() != null
              ? money(i.getSaleAdditionalDiscount())
              : "0"));
    }
    columns.add(new Column("Amt", 10, true, i -> money(i.getTotalAmount())));
    return columns;
  }

  /** Width the flexible Item column absorbs. */
  private int flexWidth(List<Column> columns) {
    int fixed = columns.stream().filter(c -> c.width() > 0).mapToInt(Column::width).sum();
    return LINE_WIDTH - fixed - (columns.size() - 1);
  }

  /** The flexible column's share of a page of the given width. */
  private int flexWidth(List<Column> columns, int width) {
    int fixed = columns.stream().filter(c -> c.width() > 0).mapToInt(Column::width).sum();
    return width - fixed - (columns.size() - 1);
  }

  private String renderRow(
      List<Column> columns, int flex, Function<Column, String> cell, String separator) {
    List<String> cells = new ArrayList<>();
    for (Column column : columns) {
      int width = column.width() > 0 ? column.width() : flex;
      cells.add(pad(cell.apply(column), width, column.rightAlign()));
    }
    // The separator replaces the space between columns rather than adding to it, so every
    // column keeps the width it was given whichever one is used.
    return String.join(separator, cells);
  }

  // ----------------------------------------------------------------- totals

  /**
   * The template's totals, in its order and under its labels. Notably there is no round-off row
   * and no per-rate tax table: tax is a single line, shown only when tax details are on.
   */
  private void appendTotals(List<String> out, GenerateInvoiceRequest r) {
    out.add(totalRow("TOTAL AMOUNT", r.getSubTotal()));
    BigDecimal additional = r.getSaleAdditionalDiscountTotal();
    if (visible(r.getShowAdditionalDiscount()) && isPositive(additional)) {
      out.add(totalRow("Less Discount", additional));
    }
    if (visible(r.getShowTaxDetails())) {
      out.add(totalRow("Add Tax", r.getTaxTotal()));
    }
    out.add(netAmountRow("NET AMOUNT", r.getGrandTotal()));
    // Out of the totals stack, as on the A4 bill, and now in the left column those rows
    // vacated when they moved to the right of the page.
    if (visible(r.getShowAmountSaved()) && isPositive(r.getTotalAmountSaved())) {
      out.add("AMOUNT SAVED  " + money(r.getTotalAmountSaved()));
    }
  }

  /** Prefixes the words with Rs. unless they already say so, so it cannot read "Rs. Rs.". */
  private static String rupees(String words) {
    String trimmed = words.trim();
    return trimmed.toUpperCase().startsWith("RS") ? trimmed : "Rs. " + trimmed;
  }

  private void appendFooter(List<String> out, GenerateInvoiceRequest r) {
    // Left, against the margin, and read as a sum of money: the trade bill sets the amount in
    // words as a sentence at the foot of the page, not as a centred caption.
    if (visible(r.getShowAmountInWords()) && present(r.getAmountInWords())) {
      out.add("");
      for (String line : wrap(rupees(r.getAmountInWords()), LINE_WIDTH)) {
        out.add(line);
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

  /**
   * Where the totals block begins. It sits in the right half of the page, under the amount
   * column it totals, rather than spanning the full width with the label adrift on the far
   * left of its own figure.
   */
  private static final int TOTALS_INDENT = 40;

  private static String totalRow(String label, BigDecimal amount) {
    String value = money(amount);
    int gap = Math.max(1, LINE_WIDTH - TOTALS_INDENT - label.length() - value.length());
    return " ".repeat(TOTALS_INDENT) + label + " ".repeat(gap) + value;
  }

  /**
   * The closing figure, at double width and emphasised.
   *
   * <p>The gap is measured against twice the value's length, since each of its characters
   * occupies two printing columns; measuring it by character count would push the figure off
   * the right margin by half its own width.
   */
  private static String netAmountRow(String label, BigDecimal amount) {
    String value = money(amount);
    int gap = Math.max(1, LINE_WIDTH - TOTALS_INDENT - label.length() - value.length() * 2);
    return " ".repeat(TOTALS_INDENT) + BOLD_ON + label + " ".repeat(gap)
        + doubleWide(value) + BOLD_OFF;
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

  private static String rule(int width) {
    return "-".repeat(width);
  }

  private static String centre(String raw, int width) {
    String value = clean(raw);
    if (value.length() >= width) {
      return value.substring(0, width);
    }
    return " ".repeat((width - value.length()) / 2) + value;
  }

  private static String centre(String raw) {
    String value = clean(raw);
    if (value.length() >= LINE_WIDTH) {
      return value.substring(0, LINE_WIDTH);
    }
    return " ".repeat((LINE_WIDTH - value.length()) / 2) + value;
  }

  private static String twoColumn(String left, String right, int width) {
    String l = clean(left);
    String r = clean(right);
    int half = width / 2;
    if (l.length() > half) {
      l = l.substring(0, half);
    }
    if (r.length() > width - half) {
      r = r.substring(0, width - half);
    }
    int gap = Math.max(1, width - l.length() - r.length());
    String line = l + " ".repeat(gap) + r;
    return line.length() > width ? line.substring(0, width) : line;
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
