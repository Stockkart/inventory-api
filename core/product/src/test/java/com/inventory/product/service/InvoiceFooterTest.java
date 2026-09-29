package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.documentservice.rest.dto.InvoiceTaxRateRow;
import com.inventory.product.domain.model.PurchaseItem;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceFooterTest {

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

  private static InvoiceService.InvoiceFooter footerOf(PurchaseItem... items) {
    InvoiceService.InvoiceFooter footer = new InvoiceService.InvoiceFooter();
    for (PurchaseItem item : items) {
      footer.add(item);
    }
    footer.split();
    return footer;
  }

  @Test
  void mixedRateBillGetsARowPerRate() {
    // T001312: GASEX and BONNISAN at 5%, LC FACE LOTION at 18%. The printed footer put all
    // 26.92 of tax under "2.5 %".
    InvoiceService.InvoiceFooter footer = footerOf(
        line("1", "150", "114.28", "6", "2.5", "112.79"),
        line("1", "90", "67.87", "2", "9", "78.48"),
        line("3", "89", "67.80", "6", "2.5", "200.76"));

    assertTrue(footer.isUsable());
    assertEquals(new BigDecimal("385.55"), footer.gross);
    assertEquals(new BigDecimal("365.13"), footer.taxable);
    List<InvoiceTaxRateRow> rows = List.copyOf(footer.rows.values());
    assertEquals(2, rows.size());
    assertEquals(new BigDecimal("298.62"), rows.get(0).getTaxableValue());
    assertEquals(new BigDecimal("14.93"), rows.get(0).getCgstAmount().add(rows.get(0).getSgstAmount()));
    assertEquals(new BigDecimal("66.51"), rows.get(1).getTaxableValue());
    assertEquals(new BigDecimal("11.97"), rows.get(1).getCgstAmount().add(rows.get(1).getSgstAmount()));
  }

  @Test
  void lineSoldAtMrpStatesTheGstInsideIt() {
    // BABY SHAMPOO 100ML on T001317: 3 x 115 less 30% is 241.50, all of it charged, 11.50 of it GST.
    InvoiceService.InvoiceFooter footer = footerOf(line("3", "115", "115", "30", "2.5", "241.50"));

    assertEquals(new BigDecimal("328.56"), footer.gross);
    assertEquals(new BigDecimal("230.00"), footer.taxable);
    assertEquals(new BigDecimal("11.50"), footer.cgst.add(footer.sgst));
  }

  @Test
  void halvesOfARateDoNotDriftApart() {
    // Four 5% lines, each with an odd paisa of tax: split per line, CGST took the half-paisa
    // every time and ran two paise ahead of SGST.
    InvoiceService.InvoiceFooter footer = footerOf(
        line("1", "150", "114.28", "6", "2.5", "112.79"),
        line("3", "89", "67.80", "6", "2.5", "200.76"),
        line("1", "418", "326.44", "5", "2.5", "325.62"),
        line("1", "394", "300.20", "5", "2.5", "299.45"));
    InvoiceTaxRateRow row = footer.rows.values().iterator().next();
    assertTrue(row.getCgstAmount().subtract(row.getSgstAmount()).abs().compareTo(new BigDecimal("0.01")) <= 0);
  }

  @Test
  void rateSpelledTwoWaysIsOneRow() {
    InvoiceService.InvoiceFooter footer = footerOf(
        line("2", "501", "501", "25", "9.00", "751.50"),
        line("2", "269", "269", "25", "9", "403.50"));
    assertEquals(1, footer.rows.size());
  }

  @Test
  void billWithNoTaxKeepsTheHeaderFooter() {
    PurchaseItem basic = line("1", "100", "100", "0", "2.5", "100");
    basic.setCgst(null);
    basic.setSgst(null);
    assertFalse(footerOf(basic).isUsable());
  }
}
