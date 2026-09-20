package com.inventory.documentservice.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.inventory.documentservice.domain.KotStamp;
import com.inventory.documentservice.rest.dto.GenerateKotRequest;
import com.inventory.documentservice.rest.dto.KotItem;
import com.inventory.metrics.MetricsWrapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class KotPdfServiceTest {

  private KotPdfService service;

  @BeforeEach
  void setUp() {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode(TemplateMode.HTML);
    resolver.setCharacterEncoding("UTF-8");
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);

    service = new KotPdfService(engine, new HtmlToPdfConverter(), mock(MetricsWrapper.class));
  }

  private GenerateKotRequest request(KotStamp stamp) {
    GenerateKotRequest r = new GenerateKotRequest();
    r.setKotNo(41);
    r.setOrderNo(7);
    r.setOrderType("DINE_IN");
    r.setTableLabel("T4");
    r.setDepartment("KITCHEN");
    r.setRoundNo(1);
    r.setPrintedAt("20/09 14:05");
    r.setStewardName("Asha");
    r.setStamp(stamp);
    r.setVoidReason(stamp == KotStamp.CANCELLED ? "table left" : null);
    KotItem item = new KotItem();
    item.setName("Biryani");
    item.setQuantity(2);
    item.setNote("no onion");
    r.setItems(List.of(item));
    return r;
  }

  @Test
  void rendersItemsQuantitiesAndNotes() {
    String html = service.renderKotHtml(request(KotStamp.NONE));

    assertTrue(html.contains("Biryani"), html);
    assertTrue(html.contains("no onion"), html);
    assertTrue(html.contains("T4"), html);
    assertTrue(html.contains("KITCHEN"), html);
    assertTrue(html.contains("41"), html);
  }

  @Test
  void carriesNoCommercialData() {
    String html = service.renderKotHtml(request(KotStamp.NONE));

    assertFalse(html.contains("GST"), html);
    assertFalse(html.contains("GSTIN"), html);
    assertFalse(html.contains("Total"), html);
    assertFalse(html.contains("MRP"), html);
    assertFalse(html.contains("₹"), html);
  }

  @Test
  void unstampedTicketShowsNoStamp() {
    String html = service.renderKotHtml(request(KotStamp.NONE));

    assertFalse(html.contains("REPRINT"), html);
    assertFalse(html.contains("CANCELLED"), html);
  }

  @Test
  void reprintIsStamped() {
    assertTrue(service.renderKotHtml(request(KotStamp.REPRINT)).contains("REPRINT"));
  }

  @Test
  void cancellationIsStampedAndCarriesItsReason() {
    String html = service.renderKotHtml(request(KotStamp.CANCELLED));

    assertTrue(html.contains("CANCELLED"), html);
    assertTrue(html.contains("table left"), html);
  }

  @Test
  void aMissingStampIsTreatedAsNone() {
    GenerateKotRequest r = request(KotStamp.NONE);
    r.setStamp(null);

    String html = service.renderKotHtml(r);

    assertFalse(html.contains("REPRINT"), html);
    assertTrue(html.contains("Biryani"), html);
  }

  @Test
  void takeawayShowsTokenInsteadOfTable() {
    GenerateKotRequest r = request(KotStamp.NONE);
    r.setOrderType("TAKEAWAY");
    r.setTableLabel(null);
    r.setTokenNo("12");

    String html = service.renderKotHtml(r);

    assertTrue(html.contains("12"), html);
    assertFalse(html.contains("Table"), html);
  }

  @Test
  void aLineWithoutANoteRendersNoNoteRow() {
    GenerateKotRequest r = request(KotStamp.NONE);
    KotItem plain = new KotItem();
    plain.setName("Water");
    plain.setQuantity(1);
    plain.setNote(null);
    r.setItems(List.of(plain));

    String html = service.renderKotHtml(r);

    assertTrue(html.contains("Water"), html);
    assertFalse(html.contains("**"), html);
  }

  @Test
  void partialCancellationIsStampedAndWarnsTheKitchen() {
    GenerateKotRequest r = request(KotStamp.PARTIAL_CANCELLATION);
    r.setVoidReason("customer changed mind");

    String html = service.renderKotHtml(r);

    assertTrue(html.contains("PARTIAL CANCELLATION"), html);
    assertFalse(html.contains("PARTIAL_CANCELLATION"), "enum name must not reach paper");
    assertTrue(html.contains("DO NOT MAKE"), html);
    assertTrue(html.contains("customer changed mind"), html);
  }

  @Test
  void aNormalTicketNeverWarnsTheKitchenOff() {
    assertFalse(service.renderKotHtml(request(KotStamp.NONE)).contains("DO NOT MAKE"));
    assertFalse(service.renderKotHtml(request(KotStamp.REPRINT)).contains("DO NOT MAKE"));
  }

  @Test
  void producesPdfBytes() {
    byte[] pdf = service.generateKotPdf(request(KotStamp.NONE));

    assertTrue(pdf.length > 0);
    assertTrue(new String(pdf, 0, 4, StandardCharsets.US_ASCII).equals("%PDF"));
  }
}
