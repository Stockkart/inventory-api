package com.inventory.documentservice.service;

import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.rest.dto.InvoiceItem;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The invoice as a dot-matrix printer wants it: characters and control codes.
 *
 * <p>A dot-matrix printer draws a fixed grid of characters on continuous paper.
 * It is not a page device, so rendering a page for it is a translation with
 * nothing to gain: the layout is authored in columns, and turning it into A4
 * only re-measures those columns in millimetres and hopes they land back where
 * they started. They do not, and a table drawn in {@code |} and {@code +---+}
 * shows every millimetre of the error.
 *
 * <p>So this emits the grid directly, in the shape the shop's own bills already
 * have -- fourteen columns, 137 characters wide, condensed so that width fits an
 * eight-inch line. The column widths are taken from those bills and are not
 * arbitrary; changing one means changing the ruler and the heading with it.
 */
@Service
public class InvoiceDotMatrixRenderer {

  /**
   * The width of each column of the item table, in characters.
   *
   * <p>Qty, pack, product, HSN, maker, expiry, batch, MRP, rate, scheme,
   * discount, SGST, CGST, amount. Fourteen columns, 122 characters, and with
   * the fifteen separators between and around them, 137.
   */
  private static final int[] COLUMNS = {8, 6, 28, 8, 8, 6, 12, 9, 8, 6, 5, 4, 4, 10};

  private static final String[] HEADINGS = {
      "QTY.", "PACK.", "PRODUCTS", "HSN/SAC", "MFG/MKD.", "Exp.Dt", "BATCH No.",
      "M.R.P.", "RATE", "SCHEME", "DIS%", "SGST", "CGST", "AMOUNT"};

  /** How each column sits in its width, matching the shop's existing bills. */
  private enum Align { LEFT, CENTRE, RIGHT }

  /**
   * Quantity and batch are centred, money is read from the right, and the rest
   * reads from the left -- which is what the shop's own format specifies: its
   * quantity and batch fields carry the {@code $C} flag and its amounts do not.
   */
  private static final Align[] ALIGN = {
      Align.CENTRE, Align.LEFT, Align.LEFT, Align.LEFT, Align.LEFT, Align.LEFT,
      Align.CENTRE, Align.RIGHT, Align.RIGHT, Align.CENTRE, Align.RIGHT,
      Align.RIGHT, Align.RIGHT, Align.RIGHT};

  /** The narrower width the header and footer text is set to. */
  private static final int TEXT_WIDTH = 96;

  public String render(GenerateInvoiceRequest request) {
    StringBuilder out = new StringBuilder();
    out.append(EscP.INIT);

    appendShopHeading(out, request);
    appendPartyBlock(out, request);
    appendItemTable(out, request);
    appendTotals(out, request);
    appendFooter(out, request);

    // The printer is told where the bill ends. Continuous paper is never cut,
    // so without this the next bill starts wherever this one stopped and the
    // perforation falls through the middle of a table.
    out.append(EscP.FORM_FEED);
    return out.toString();
  }

  private void appendShopHeading(StringBuilder out, GenerateInvoiceRequest request) {
    out.append(EscP.CONDENSED_OFF).append(EscP.PITCH_10);
    // Double width halves how many characters fit, so these are centred in half
    // the line rather than the whole of it.
    line(out, EscP.BOLD_ON + EscP.DOUBLE_ON
        + centre(value(request.getShopName()), TEXT_WIDTH / 2)
        + EscP.DOUBLE_OFF + EscP.BOLD_OFF);
    line(out, centre(value(request.getShopAddress()), TEXT_WIDTH));
    if (StringUtils.hasText(request.getShopGstin())) {
      line(out, centre("GST NO. : " + request.getShopGstin(), TEXT_WIDTH));
    }
    if (StringUtils.hasText(request.getShopPhone())) {
      line(out, centre("Phone : " + request.getShopPhone(), TEXT_WIDTH));
    }
    out.append(EscP.PITCH_12);
    line(out, rule('-', TEXT_WIDTH));
  }

  private void appendPartyBlock(StringBuilder out, GenerateInvoiceRequest request) {
    line(out, "M/s. " + EscP.BOLD_ON + pad(value(request.getCustomerName()), 35) + EscP.BOLD_OFF
        + "  Inv.No.: " + value(request.getInvoiceNo()));
    line(out, "     " + pad(value(request.getCustomerAddress()), 35)
        + "  GSTIN  : " + pad(value(request.getCustomerGstin()), 20)
        + "  DATE   : " + value(request.getInvoiceDate()));
    out.append(EscP.PITCH_10).append(EscP.CONDENSED_ON);
  }

  private void appendItemTable(StringBuilder out, GenerateInvoiceRequest request) {
    line(out, ruler());
    line(out, headingRow());
    line(out, ruler());

    List<InvoiceItem> items = request.getItems() != null ? request.getItems() : List.of();
    for (InvoiceItem item : items) {
      line(out, row(
          quantity(item),
          "",
          value(item.getName()),
          value(item.getHsn()),
          value(item.getCompanyName()),
          value(item.getExpiryDate()),
          value(item.getBatchNo()),
          money(item.getMaximumRetailPrice()),
          money(item.getPriceToRetail()),
          scheme(item),
          money(item.getSaleAdditionalDiscount()),
          value(item.getSgst()),
          value(item.getCgst()),
          money(item.getTotalAmount())));
    }
    line(out, ruler());
  }

  private void appendTotals(StringBuilder out, GenerateInvoiceRequest request) {
    out.append(EscP.CONDENSED_OFF);
    line(out, "");
    line(out, rightAlign("NO OF ITEMS : "
        + (request.getItems() != null ? request.getItems().size() : 0), TEXT_WIDTH - 32)
        + "  NET AMOUNT " + EscP.BOLD_ON + EscP.DOUBLE_ON
        + money(request.getGrandTotal()) + EscP.DOUBLE_OFF + EscP.BOLD_OFF);
    if (StringUtils.hasText(request.getAmountInWords())) {
      line(out, request.getAmountInWords());
    }
  }

  private void appendFooter(StringBuilder out, GenerateInvoiceRequest request) {
    line(out, rule('-', TEXT_WIDTH));
    line(out, "1.On the strength of Warranty obtained by us We hereby give this Warranty that");
    line(out, "   the goods sold by us do not contravene the provisions of the Drugs Act 1940");
    line(out, "2.Price charged excess due to oversight may be reffered to us for rectificaton."
        + "        For, " + EscP.BOLD_ON + value(request.getShopName()) + EscP.BOLD_OFF);
    line(out, "");
    line(out, "E.& O.E.                                Received By"
        + "                       Authorised Signatory");
  }

  // ---- the grid -----------------------------------------------------------

  /** {@code +--------+------+...+}, the line above and below the headings. */
  private String ruler() {
    StringBuilder ruler = new StringBuilder("+");
    for (int width : COLUMNS) {
      ruler.append(rule('-', width)).append('+');
    }
    return ruler.toString();
  }

  private String headingRow() {
    String[] cells = new String[COLUMNS.length];
    for (int i = 0; i < COLUMNS.length; i++) {
      cells[i] = centre(HEADINGS[i], COLUMNS[i]);
    }
    return row(cells);
  }

  /**
   * One line of the table.
   *
   * <p>A cell too long for its column is cut rather than allowed to run on. The
   * grid is the whole point: one wide product name would otherwise push every
   * column after it out of line for that row alone, and the table stops being
   * readable down the page.
   */
  private String row(String... cells) {
    StringBuilder line = new StringBuilder("|");
    for (int i = 0; i < COLUMNS.length; i++) {
      String cell = i < cells.length && cells[i] != null ? cells[i] : "";
      line.append(switch (ALIGN[i]) {
        case RIGHT -> rightAlign(cell, COLUMNS[i]);
        case CENTRE -> centre(cell, COLUMNS[i]);
        case LEFT -> pad(cell, COLUMNS[i]);
      });
      line.append('|');
    }
    return line.toString();
  }

  // ---- cells --------------------------------------------------------------

  private String quantity(InvoiceItem item) {
    String quantity = item.getQuantity() != null
        ? item.getQuantity().stripTrailingZeros().toPlainString() : "";
    // A scheme is written the way the trade writes it: paid-for plus free.
    if (item.getSchemeFree() != null && item.getSchemeFree() > 0) {
      return quantity + "+" + item.getSchemeFree();
    }
    return quantity;
  }

  private String scheme(InvoiceItem item) {
    if (item.getSchemePayFor() != null && item.getSchemeFree() != null) {
      return item.getSchemePayFor() + "+" + item.getSchemeFree();
    }
    return item.getScheme() != null && item.getScheme() > 0 ? String.valueOf(item.getScheme()) : "";
  }

  private String money(BigDecimal amount) {
    return amount == null ? "" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private String value(String text) {
    return text == null ? "" : text.trim();
  }

  // ---- characters ---------------------------------------------------------

  private void line(StringBuilder out, String text) {
    out.append(text).append("\r\n");
  }

  private String rule(char of, int width) {
    return String.valueOf(of).repeat(Math.max(0, width));
  }

  private String pad(String text, int width) {
    String cut = cut(text, width);
    return cut + " ".repeat(width - cut.length());
  }

  private String rightAlign(String text, int width) {
    String cut = cut(text, width);
    return " ".repeat(width - cut.length()) + cut;
  }

  private String centre(String text, int width) {
    String cut = cut(text, width);
    int left = (width - cut.length()) / 2;
    return " ".repeat(left) + cut + " ".repeat(width - cut.length() - left);
  }

  private String cut(String text, int width) {
    String safe = text == null ? "" : text;
    return safe.length() <= width ? safe : safe.substring(0, width);
  }
}
