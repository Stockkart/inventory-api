package com.inventory.documentservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.rest.dto.InvoiceItem;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceTextRendererTest {

  private final InvoiceTextRenderer renderer = new InvoiceTextRenderer();

  private static InvoiceItem item(String name, String batch, String expiry) {
    InvoiceItem i = new InvoiceItem();
    i.setName(name);
    i.setBatchNo(batch);
    i.setExpiryDate(expiry);
    i.setQuantity(new BigDecimal("10"));
    i.setMaximumRetailPrice(new BigDecimal("120"));
    i.setPriceToRetail(new BigDecimal("100"));
    i.setTotalAmount(new BigDecimal("1000"));
    return i;
  }

  private static GenerateInvoiceRequest request() {
    GenerateInvoiceRequest r = new GenerateInvoiceRequest();
    r.setInvoiceNo("INV-001");
    r.setInvoiceDate("24-08-2026");
    r.setInvoiceTime("07:15 PM");
    r.setShopName("SHARDA MEDICALS");
    r.setShopAddress("12 Main Road, Pune, Maharashtra, 411001");
    r.setShopPhone("9876543210");
    r.setShopGstin("27ABCDE1234F1Z5");
    r.setCustomerName("Ramesh Kumar");
    r.setCustomerPhone("9123456780");
    r.setItems(List.of(item("PARACETAMOL 500MG", "B12345", "12/2027")));
    r.setSubTotal(new BigDecimal("1000"));
    r.setDiscountTotal(BigDecimal.ZERO);
    r.setCgstAmount(new BigDecimal("53.57"));
    r.setSgstAmount(new BigDecimal("53.57"));
    r.setRoundOff(BigDecimal.ZERO);
    r.setGrandTotal(new BigDecimal("1107.14"));
    r.setAmountInWords("RUPEES ONE THOUSAND ONE HUNDRED SEVEN AND FOURTEEN PAISE ONLY");
    return r;
  }

  @Test
  void everyLineIsAtMostEightyColumns() {
    String out = renderer.render(request());
    for (String line : out.split("\n", -1)) {
      assertTrue(line.length() <= InvoiceTextRenderer.LINE_WIDTH,
          "line over 80 cols (" + line.length() + "): [" + line + "]");
    }
  }

  @Test
  void emitsNoControlCharactersExceptNewline() {
    String out = renderer.render(request());
    assertTrue(out.chars().noneMatch(c -> c < 32 && c != '\n'),
        "renderer must emit no control characters other than newline");
  }

  @Test
  void controlCharactersInFieldsAreNeutralisedAndCannotSplitALine() {
    GenerateInvoiceRequest clean = request();
    int cleanLineCount = renderer.render(clean).split("\n", -1).length;

    // Every field the finding calls out as unsanitised and unwrapped, each carrying a CR, LF or
    // tab in the middle. None of these route through wrap(), which happens to be safe already.
    GenerateInvoiceRequest r = request();
    r.setInvoiceNo("INV\n001");
    r.setInvoiceDate("24-08\r2026");
    r.setInvoiceTime("07:15\tPM");
    r.setCustomerName("Ramesh\nKumar");
    r.setCustomerPhone("9123\r456780");
    r.setShopName("SHARDA\nMEDICALS");
    r.setItems(List.of(item("PARA\nCETAMOL", "B1\r2345", "12\n2027")));

    String out = renderer.render(r);

    assertTrue(out.chars().noneMatch(c -> c < 32 && c != '\n'),
        "control characters embedded in fields must not leak into the rendered text");
    assertEquals(cleanLineCount, out.split("\n", -1).length,
        "an embedded CR/LF must not add a physical line and break the column layout");
  }

  @Test
  void emojiInCustomerNameFoldsToQuestionMarkAndKeepsRuneCountAtLineWidth() {
    // An astral-plane character (e.g. an emoji copied from a phone contact) is 2 UTF-16 code
    // units in Java but 1 rune in the Go encoder. Without folding, Java's String.length() over-
    // counts it by one, so a line Java measures as LINE_WIDTH prints one column short on paper.
    GenerateInvoiceRequest r = request();
    r.setCustomerName("Ramesh 😀 Kumar");
    String row = renderer.render(r).lines()
        .filter(l -> l.contains("Customer")).findFirst().orElseThrow();

    assertFalse(row.chars().anyMatch(c -> c < 0x20 || c > 0x7E),
        "non-ASCII characters such as an emoji must be folded to '?' before reaching the page");
    assertEquals(InvoiceTextRenderer.LINE_WIDTH, row.codePointCount(0, row.length()),
        "line must be exactly LINE_WIDTH runes, matching what the Go encoder will print");
  }

  @Test
  void particularsColumnIsTwentyFourWhenAllColumnsEnabled() {
    String header = headerRow(renderer.render(request()));
    assertEquals(InvoiceTextRenderer.LINE_WIDTH, header.length(), "header must fill the line");
    // BATCH is left-aligned, so its label starts exactly where its column starts.
    int start = header.indexOf("PARTICULARS");
    int batchStart = header.indexOf("BATCH");
    assertEquals(24, batchStart - start - 1, "PARTICULARS width should be 24");
  }

  @Test
  void particularsColumnWidensToFiftyTwoWhenBatchExpiryMrpHidden() {
    GenerateInvoiceRequest r = request();
    r.setShowBatch(false);
    r.setShowExpiry(false);
    r.setShowMrp(false);
    String header = headerRow(renderer.render(r));
    assertEquals(InvoiceTextRenderer.LINE_WIDTH, header.length(), "header must fill the line");
    assertFalse(header.contains("BATCH"));
    assertFalse(header.contains("EXPIRY"));
    assertFalse(header.contains("MRP"));
    // QTY is right-aligned in a 5-wide column, so its label ENDS at the column end.
    int start = header.indexOf("PARTICULARS");
    int qtyStart = header.indexOf("QTY") + "QTY".length() - 5;
    assertEquals(52, qtyStart - start - 1, "PARTICULARS width should widen to 52");
  }

  @Test
  void longProductNameIsTruncatedNotWrapped() {
    GenerateInvoiceRequest r = request();
    r.setItems(List.of(item("A".repeat(60), "B1", "01/2030")));
    String out = renderer.render(r);
    assertFalse(out.contains("A".repeat(25)), "name must be truncated to the 24-col field");
    assertTrue(out.contains("A".repeat(24)));
  }

  @Test
  void taxBlockOmittedWhenShowTaxDetailsFalse() {
    GenerateInvoiceRequest r = request();
    r.setShowTaxDetails(false);
    assertFalse(renderer.render(r).contains("TAXABLE"));
  }

  @Test
  void nullMoneyRendersBlankNotZero() {
    GenerateInvoiceRequest r = request();
    InvoiceItem i = item("NO MRP ITEM", "B9", "05/2029");
    i.setMaximumRetailPrice(null);
    r.setItems(List.of(i));
    String out = renderer.render(r);
    String header = headerRow(out);
    String row = out.lines()
        .filter(l -> l.contains("NO MRP ITEM")).findFirst().orElseThrow();
    // MRP is right-aligned in an 8-wide column, so its label ends at the column end.
    int mrpEnd = header.indexOf("MRP") + "MRP".length();
    assertEquals(" ".repeat(8), row.substring(mrpEnd - 8, mrpEnd),
        "null MRP must render as blanks, not 0.00");
  }

  @Test
  void grandTotalRowIsRightAlignedToEighty() {
    String row = renderer.render(request()).lines()
        .filter(l -> l.contains("GRAND TOTAL")).findFirst().orElseThrow();
    assertEquals(InvoiceTextRenderer.LINE_WIDTH, row.length());
    assertTrue(row.endsWith("1107.14"));
  }

  private static String headerRow(String out) {
    return out.lines().filter(l -> l.contains("PARTICULARS")).findFirst().orElseThrow();
  }
}
