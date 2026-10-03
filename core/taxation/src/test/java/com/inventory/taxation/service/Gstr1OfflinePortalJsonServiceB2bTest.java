package com.inventory.taxation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstInvoiceLine;
import com.inventory.taxation.dto.gstr1offline.Gstr1PortalReturnDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The portal refuses a payload that names one invoice twice, so an invoice
 * carrying goods at more than one rate has to arrive as one invoice holding an
 * item per rate.
 */
class Gstr1OfflinePortalJsonServiceB2bTest {

  private final Gstr1OfflinePortalJsonService service =
      new Gstr1OfflinePortalJsonService(new com.fasterxml.jackson.databind.ObjectMapper());

  /** T001031 as the shop actually raised it: ₹1,500, part at 5% and part at 18%. */
  private static List<GstInvoiceLine> twoRatesOnOneInvoice() {
    return List.of(
        line("T001031", "10ATAPK0829J1Z5", new BigDecimal("18.0"),
            new BigDecimal("622.88"), new BigDecimal("56.06")),
        line("T001031", "10ATAPK0829J1Z5", new BigDecimal("5.0"),
            new BigDecimal("728.57"), new BigDecimal("18.21")));
  }

  private static GstInvoiceLine line(String inum, String ctin, BigDecimal rate,
      BigDecimal taxable, BigDecimal halfTax) {
    return GstInvoiceLine.builder()
        .invoiceNo(inum)
        .invoiceDate(LocalDate.of(2026, 8, 14))
        .invoiceValue(new BigDecimal("1500.00"))
        .recipientGstin(ctin)
        .placeOfSupply("10-Bihar")
        .reverseCharge("N")
        .invoiceType("Regular")
        .rate(rate)
        .taxableValue(taxable)
        .integratedTaxAmount(BigDecimal.ZERO)
        .centralTaxAmount(halfTax)
        .stateTaxAmount(halfTax)
        .cessAmount(BigDecimal.ZERO)
        .build();
  }

  private Gstr1PortalReturnDto build(List<GstInvoiceLine> b2bLines) {
    Gstr1ReportContext ctx = Gstr1ReportContext.builder()
        .shopGstin("10AFBPL7000H1Z8")
        .year(2026)
        .month(8)
        .b2bLines(new ArrayList<>(b2bLines))
        .b2csLines(new ArrayList<>())
        .hsnB2bLines(new ArrayList<>())
        .hsnB2cLines(new ArrayList<>())
        .docLines(new ArrayList<>())
        .build();
    return service.buildDto(ctx);
  }

  @Test
  @DisplayName("an invoice at two rates is one invoice with two items")
  void oneInvoiceTwoItems() {
    Gstr1PortalReturnDto dto = build(twoRatesOnOneInvoice());

    assertEquals(1, dto.getB2b().size(), "one recipient");
    List<Gstr1PortalReturnDto.B2bInvoiceDto> invoices = dto.getB2b().get(0).getInv();
    assertEquals(1, invoices.size(), "T001031 must appear once, not once per rate");

    Gstr1PortalReturnDto.B2bInvoiceDto inv = invoices.get(0);
    assertEquals("T001031", inv.getInum());
    assertEquals(2, inv.getItms().size(), "one item per rate");
    assertEquals(1500.0, inv.getVal(), 0.001, "the invoice's own value, not a sum of its rates");
  }

  @Test
  @DisplayName("no invoice number is named twice anywhere in the payload")
  void noDuplicateInvoiceNumbers() {
    Gstr1PortalReturnDto dto = build(twoRatesOnOneInvoice());

    Set<String> seen = new HashSet<>();
    for (Gstr1PortalReturnDto.B2bByCtinDto party : dto.getB2b()) {
      for (Gstr1PortalReturnDto.B2bInvoiceDto inv : party.getInv()) {
        assertTrue(seen.add(party.getCtin() + "|" + inv.getInum()),
            "duplicate invoice number in payload: " + inv.getInum());
      }
    }
  }

  @Test
  @DisplayName("each rate keeps its own taxable value and tax")
  void eachRateKeepsItsOwnFigures() {
    Gstr1PortalReturnDto.B2bInvoiceDto inv =
        build(twoRatesOnOneInvoice()).getB2b().get(0).getInv().get(0);

    Gstr1PortalReturnDto.ItemDetDto at18 = itemAtRate(inv, 18.0);
    assertEquals(622.88, at18.getTxval(), 0.001);
    assertEquals(56.06, at18.getCamt(), 0.001);

    Gstr1PortalReturnDto.ItemDetDto at5 = itemAtRate(inv, 5.0);
    assertEquals(728.57, at5.getTxval(), 0.001);
    assertEquals(18.21, at5.getCamt(), 0.001);
  }

  @Test
  @DisplayName("an item number is not repeated inside one invoice")
  void twoLinesAtOneRateBecomeOneItem() {
    List<GstInvoiceLine> sameRateTwice = List.of(
        line("T000900", "10ATAPK0829J1Z5", new BigDecimal("5.0"),
            new BigDecimal("100.00"), new BigDecimal("2.50")),
        line("T000900", "10ATAPK0829J1Z5", new BigDecimal("5.0"),
            new BigDecimal("200.00"), new BigDecimal("5.00")));

    Gstr1PortalReturnDto.B2bInvoiceDto inv =
        build(sameRateTwice).getB2b().get(0).getInv().get(0);

    assertEquals(1, inv.getItms().size(), "one item for the one rate");
    assertEquals(300.00, inv.getItms().get(0).getItmDet().getTxval(), 0.001);
    assertEquals(7.50, inv.getItms().get(0).getItmDet().getCamt(), 0.001);
  }

  @Test
  @DisplayName("an invoice at a single rate is unchanged")
  void singleRateInvoiceIsUnchanged() {
    Gstr1PortalReturnDto.B2bInvoiceDto inv = build(List.of(
        line("T001000", "10ATAPK0829J1Z5", new BigDecimal("5.0"),
            new BigDecimal("952.38"), new BigDecimal("23.81"))))
        .getB2b().get(0).getInv().get(0);

    assertEquals("T001000", inv.getInum());
    assertEquals(1, inv.getItms().size());
    assertEquals(952.38, inv.getItms().get(0).getItmDet().getTxval(), 0.001);
  }

  private static Gstr1PortalReturnDto.ItemDetDto itemAtRate(
      Gstr1PortalReturnDto.B2bInvoiceDto inv, double rate) {
    return inv.getItms().stream()
        .map(Gstr1PortalReturnDto.B2bLineItemDto::getItmDet)
        .filter(det -> Math.abs(det.getRt() - rate) < 0.001)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no item at rate " + rate));
  }
}
