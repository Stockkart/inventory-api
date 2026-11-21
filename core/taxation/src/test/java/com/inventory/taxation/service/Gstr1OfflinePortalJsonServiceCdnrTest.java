package com.inventory.taxation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstDocumentSummaryLine;
import com.inventory.taxation.domain.model.GstHsnLine;
import com.inventory.taxation.domain.model.GstRefundLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The upload carries credit notes, numbers table 13 the portal's way, and keeps HSN rows short. */
class Gstr1OfflinePortalJsonServiceCdnrTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final Gstr1OfflinePortalJsonService service = new Gstr1OfflinePortalJsonService(mapper);

  /** CN-00003 as Kuber raised it in October 2026. */
  private static GstRefundLine cn00003() {
    return GstRefundLine.builder()
        .registered(true)
        .recipientGstin("10BAHPK9801K1ZS")
        .noteNumber("CN-00003")
        .noteDate(LocalDate.of(2026, 10, 15))
        .noteType("C")
        .placeOfSupply("10-Bihar")
        .reverseCharge("N")
        .noteValue(new BigDecimal("1031"))
        .rate(new BigDecimal("5"))
        .taxableValue(new BigDecimal("982.04"))
        .integratedTaxAmount(BigDecimal.ZERO)
        .centralTaxAmount(new BigDecimal("24.56"))
        .stateTaxAmount(new BigDecimal("24.56"))
        .cessAmount(BigDecimal.ZERO)
        .build();
  }

  private JsonNode upload(List<GstRefundLine> cdnr, List<GstDocumentSummaryLine> docs) throws Exception {
    GstHsnLine hsn = GstHsnLine.builder()
        .hsn("30049011")
        .description("MEDICAMENTS (EXCLUDING GOODS OF HEADING 3002, 3005 OR 3006) CONSISTING OF ...")
        .uqc("PCS-PIECES")
        .totalQuantity(BigDecimal.TEN)
        .rate(new BigDecimal("5"))
        .taxableValue(new BigDecimal("1000"))
        .integratedTaxAmount(BigDecimal.ZERO)
        .centralTaxAmount(new BigDecimal("25"))
        .stateUtTaxAmount(new BigDecimal("25"))
        .cessAmount(BigDecimal.ZERO)
        .build();
    Gstr1ReportContext ctx = Gstr1ReportContext.builder()
        .shopGstin("10AFBPL7000H1Z8")
        .year(2026)
        .month(10)
        .b2bLines(new ArrayList<>())
        .b2csLines(new ArrayList<>())
        .cdnrLines(new ArrayList<>(cdnr))
        .hsnB2bLines(new ArrayList<>(List.of(hsn)))
        .hsnB2cLines(new ArrayList<>())
        .docLines(new ArrayList<>(docs))
        .build();
    return mapper.readTree(service.toJsonUtf8(ctx));
  }

  @Test
  void aRegisteredBuyersNoteIsWrittenToCdnr() throws Exception {
    JsonNode json = upload(List.of(cn00003()), List.of());

    JsonNode block = json.get("cdnr").get(0);
    assertEquals("10BAHPK9801K1ZS", block.get("ctin").asText());
    JsonNode note = block.get("nt").get(0);
    assertEquals("C", note.get("ntty").asText());
    assertEquals("CN-00003", note.get("nt_num").asText());
    assertEquals("15-10-2026", note.get("nt_dt").asText());
    assertEquals(1031.0, note.get("val").asDouble());
    assertEquals("10", note.get("pos").asText());
    assertEquals("R", note.get("inv_typ").asText());
    JsonNode item = note.get("itms").get(0);
    assertEquals(501, item.get("num").asInt());
    assertEquals(982.04, item.get("itm_det").get("txval").asDouble());
    assertEquals(24.56, item.get("itm_det").get("camt").asDouble());
    assertEquals(24.56, item.get("itm_det").get("samt").asDouble());
    assertFalse(item.get("itm_det").has("iamt"));
  }

  @Test
  void aPeriodWithoutNotesHasNoCdnrKey() throws Exception {
    assertFalse(upload(List.of(), List.of()).has("cdnr"));
  }

  @Test
  void creditNotesAreDocumentFiveNotTheNextNumber() throws Exception {
    List<GstDocumentSummaryLine> docs = List.of(
        GstDocumentSummaryLine.builder().natureOfDocument("Invoices for outward supply")
            .srNoFrom("T001337").srNoTo("T001489").totalNumber(153).cancelled(0).build(),
        GstDocumentSummaryLine.builder().natureOfDocument("Credit Note")
            .srNoFrom("CN-00003").srNoTo("CN-00003").totalNumber(1).cancelled(0).build());

    JsonNode docDet = upload(List.of(), docs).get("doc_issue").get("doc_det");

    assertEquals(1, docDet.get(0).get("doc_num").asInt());
    assertEquals(5, docDet.get(1).get("doc_num").asInt());
    assertEquals("Credit Note", docDet.get(1).get("doc_typ").asText());
  }

  @Test
  void hsnRowsCarryNoDescription() throws Exception {
    JsonNode row = upload(List.of(), List.of()).get("hsn").get("hsn_b2b").get(0);

    assertTrue(row.get("desc").asText().isEmpty());
    assertEquals("30049011", row.get("hsn_sc").asText());
  }
}
