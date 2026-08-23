package com.inventory.taxation.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.taxation.domain.model.GstHsnLine;
import com.inventory.taxation.domain.model.GstInvoiceLine;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A tab holds one row per tax rate, so an invoice bearing two rates appears twice
 * and so does an HSN sold at two. The header counts the things themselves, as the
 * portal does: on one real month it read 60 invoices against the 57 filed.
 */
class GstTabSummaryCountTest {

  private static GstInvoiceLine invoiceRow(String invoiceNo, String rate, String taxable) {
    return GstInvoiceLine.builder()
        .recipientGstin("10AAACX1234A1Z5")
        .invoiceNo(invoiceNo)
        .invoiceValue(new BigDecimal("1500.00"))
        .rate(new BigDecimal(rate))
        .taxableValue(new BigDecimal(taxable))
        .build();
  }

  private static GstHsnLine hsnRow(String hsn, String rate, String taxable) {
    GstHsnLine line = new GstHsnLine();
    line.setHsn(hsn);
    line.setRate(new BigDecimal(rate));
    line.setTaxableValue(new BigDecimal(taxable));
    line.setTotalValue(new BigDecimal(taxable));
    return line;
  }

  @Test
  void b2bCountsInvoicesNotRows() {
    List<GstInvoiceLine> lines = List.of(
        invoiceRow("T001031", "18", "622.88"),
        invoiceRow("T001031", "5", "728.57"),
        invoiceRow("T001032", "5", "100.00"));

    assertEquals(2, GstB2bSezDeTabDto.fromLines(lines).getSummary().getNoOfInvoices());
  }

  @Test
  void b2bStillSumsTaxableOverEveryRow() {
    List<GstInvoiceLine> lines = List.of(
        invoiceRow("T001031", "18", "622.88"),
        invoiceRow("T001031", "5", "728.57"));

    assertEquals(new BigDecimal("1351.45"),
        GstB2bSezDeTabDto.fromLines(lines).getSummary().getTaxableValue());
  }

  @Test
  void hsnCountsCodesNotRows() {
    List<GstHsnLine> lines = List.of(
        hsnRow("33049990", "5", "10.00"),
        hsnRow("33049990", "18", "20.00"),
        hsnRow("30049011", "5", "30.00"));

    assertEquals(2, GstHsnTabDto.fromLines(lines).getSummary().getNoOfHsn());
  }
}
