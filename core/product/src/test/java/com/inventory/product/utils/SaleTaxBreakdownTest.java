package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.rest.dto.response.GstRateRowDto;
import com.inventory.product.rest.dto.response.SaleTaxSummaryDto;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SaleTaxBreakdownTest {

  private static PurchaseItem line(String qty, String mrp, String rate, String addl, String gst, String amount) {
    PurchaseItem item = new PurchaseItem();
    item.setQuantity(new BigDecimal(qty));
    item.setMaximumRetailPrice(new BigDecimal(mrp));
    item.setPriceToRetail(new BigDecimal(rate));
    item.setSaleAdditionalDiscount(new BigDecimal(addl));
    item.setCgst(gst);
    item.setSgst(gst);
    item.setTotalAmount(new BigDecimal(amount));
    return item;
  }

  private static java.util.Optional<SaleTaxSummaryDto> summaryOf(PurchaseItem... items) {
    Purchase purchase = new Purchase();
    purchase.setItems(List.of(items));
    return SaleTaxBreakdown.of(purchase);
  }

  private static SaleTaxSummaryDto footerOf(PurchaseItem... items) {
    return summaryOf(items).orElseThrow();
  }

  @Test
  void mixedRateBillGetsARowPerRate() {
    // T001312: GASEX and BONNISAN at 5%, LC FACE LOTION at 18%. The printed footer put all
    // 26.92 of tax under "2.5 %".
    SaleTaxSummaryDto footer = footerOf(
        line("1", "150", "114.28", "6", "2.5", "112.79"),
        line("1", "90", "67.87", "2", "9", "78.48"),
        line("3", "89", "67.80", "6", "2.5", "200.76"));

        assertEquals(new BigDecimal("385.55"), footer.getSubTotal());
    assertEquals(new BigDecimal("365.13"), footer.getTaxableValue());
    List<GstRateRowDto> rows = footer.getRates();
    assertEquals(2, rows.size());
    assertEquals(new BigDecimal("298.62"), rows.get(0).getTaxableValue());
    assertEquals(new BigDecimal("14.93"), rows.get(0).getCgstAmount().add(rows.get(0).getSgstAmount()));
    assertEquals(new BigDecimal("66.51"), rows.get(1).getTaxableValue());
    assertEquals(new BigDecimal("11.97"), rows.get(1).getCgstAmount().add(rows.get(1).getSgstAmount()));
  }

  @Test
  void lineSoldAtMrpStatesTheGstInsideIt() {
    // BABY SHAMPOO 100ML on T001317: 3 x 115 less 30% is 241.50, all of it charged, 11.50 of it GST.
    SaleTaxSummaryDto footer = footerOf(line("3", "115", "115", "30", "2.5", "241.50"));

    assertEquals(new BigDecimal("328.56"), footer.getSubTotal());
    assertEquals(new BigDecimal("230.00"), footer.getTaxableValue());
    assertEquals(new BigDecimal("11.50"), footer.getCgstTotal().add(footer.getSgstTotal()));
  }

  @Test
  void halvesOfARateDoNotDriftApart() {
    // Four 5% lines, each with an odd paisa of tax: split per line, CGST took the half-paisa
    // every time and ran two paise ahead of SGST.
    SaleTaxSummaryDto footer = footerOf(
        line("1", "150", "114.28", "6", "2.5", "112.79"),
        line("3", "89", "67.80", "6", "2.5", "200.76"),
        line("1", "418", "326.44", "5", "2.5", "325.62"),
        line("1", "394", "300.20", "5", "2.5", "299.45"));
    GstRateRowDto row = footer.getRates().get(0);
    assertTrue(row.getCgstAmount().subtract(row.getSgstAmount()).abs().compareTo(new BigDecimal("0.01")) <= 0);
  }

  @Test
  void everyGstSlabGetsItsOwnRowAtItsOwnRate() {
    // Nothing is tied to 2.5% or 9%: each rate a line carries becomes its own row, and each
    // row's tax is taken out of its lines' amounts at that rate. 1000 taxable at every slab.
    String[][] slabs = {{"0.125", "1002.50"}, {"0.75", "1015.00"}, {"1.5", "1030.00"},
        {"2.5", "1050.00"}, {"6", "1120.00"}, {"9", "1180.00"}, {"14", "1280.00"}};
    PurchaseItem[] lines = new PurchaseItem[slabs.length];
    for (int i = 0; i < slabs.length; i++) {
      lines[i] = line("1", "5000", "1000", "0", slabs[i][0], slabs[i][1]);
    }
    SaleTaxSummaryDto footer = footerOf(lines);

    assertEquals(slabs.length, footer.getRates().size());
    for (String[] slab : slabs) {
      BigDecimal half = new BigDecimal(slab[0]);
      GstRateRowDto row = footer.getRates().stream()
          .filter(r -> r.getCgstPercent().compareTo(half) == 0)
          .findFirst().orElseThrow();
      assertEquals(0, row.getTaxableValue().compareTo(new BigDecimal("1000.00")), slab[0]);
      BigDecimal expectedHalf = new BigDecimal("10").multiply(half).setScale(2, java.math.RoundingMode.HALF_UP);
      assertEquals(expectedHalf, row.getCgstAmount(), "CGST at " + slab[0]);
      assertEquals(new BigDecimal(slab[1]).subtract(new BigDecimal("1000")).subtract(expectedHalf), row.getSgstAmount(), "SGST at " + slab[0]);
    }
  }

  @Test
  void statesEachLineRateBeforeTaxAndTheRoundOffToTheBilledTotal() {
    // T001312: rates as printed, and 365.13 taxable + 26.90 GST against a 392 bill.
    Purchase purchase = new Purchase();
    purchase.setItems(List.of(
        line("1", "150", "114.28", "6", "2.5", "112.79"),
        line("1", "90", "67.87", "2", "9", "78.48"),
        line("3", "115", "115", "30", "2.5", "241.50")));
    purchase.setGrandTotal(new BigDecimal("433"));
    SaleTaxSummaryDto summary = SaleTaxBreakdown.of(purchase).orElseThrow();

    // The MRP line prints 109.52, its price with the 5% inside it taken out.
    assertEquals(List.of(new BigDecimal("114.28"), new BigDecimal("67.87"), new BigDecimal("109.52")),
        summary.getLineRates());
    BigDecimal tax = summary.getCgstTotal().add(summary.getSgstTotal());
    assertEquals(0, new BigDecimal("433").compareTo(summary.getTaxableValue().add(tax).add(summary.getRoundOff())));
  }

  @Test
  void rateSpelledTwoWaysIsOneRow() {
    SaleTaxSummaryDto footer = footerOf(
        line("2", "501", "501", "25", "9.00", "751.50"),
        line("2", "269", "269", "25", "9", "403.50"));
    assertEquals(1, footer.getRates().size());
  }

  @Test
  void billWithNoTaxKeepsTheHeaderFooter() {
    PurchaseItem basic = line("1", "100", "100", "0", "2.5", "100");
    basic.setCgst(null);
    basic.setSgst(null);
    assertFalse(summaryOf(basic).isPresent());
  }

  @Test
  void anInterstateSaleStatesItsTaxAsIgstPerRate() {
    Purchase purchase = new Purchase();
    purchase.setInterstate(true);
    purchase.setItems(List.of(
        line("1", "150", "114.28", "6", "2.5", "112.79"),
        line("1", "90", "67.87", "2", "9", "78.48")));

    SaleTaxSummaryDto footer = SaleTaxBreakdown.of(purchase).orElseThrow();

    assertEquals(0, BigDecimal.ZERO.compareTo(footer.getCgstTotal()));
    assertEquals(0, BigDecimal.ZERO.compareTo(footer.getSgstTotal()));
    assertEquals(new BigDecimal("5.37"), footer.getRates().get(0).getIgstAmount());
    assertEquals(new BigDecimal("11.97"), footer.getRates().get(1).getIgstAmount());
    assertEquals(new BigDecimal("17.34"), footer.getIgstTotal());
  }

  @Test
  void aRateWrittenWithAPercentSignStillCounts() {
    SaleTaxSummaryDto footer = footerOf(line("1", "90", "67.87", "2", "9%", "78.48"));

    assertEquals(new BigDecimal("11.97"),
        footer.getRates().get(0).getCgstAmount().add(footer.getRates().get(0).getSgstAmount()));
  }
}
