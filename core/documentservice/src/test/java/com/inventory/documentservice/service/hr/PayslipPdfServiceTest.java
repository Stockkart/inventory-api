package com.inventory.documentservice.service.hr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.inventory.documentservice.rest.dto.hr.PayslipDocumentRequest;
import com.inventory.documentservice.service.HtmlToPdfConverter;
import com.inventory.metrics.MetricsWrapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** The real {@code templates/hr/payslip.html} rendered to HTML and to a PDF (HR R10.2). */
class PayslipPdfServiceTest {

  private static PayslipPdfService service() {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode(TemplateMode.HTML);
    resolver.setCharacterEncoding("UTF-8");
    // SpringTemplateEngine (SpEL), as the app's auto-configuration; plain Thymeleaf needs OGNL
    TemplateEngine engine = new org.thymeleaf.spring6.SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    PayslipPdfService service = new PayslipPdfService();
    ReflectionTestUtils.setField(service, "templateEngine", engine);
    ReflectionTestUtils.setField(service, "htmlToPdfConverter", new HtmlToPdfConverter());
    ReflectionTestUtils.setField(service, "metrics", mock(MetricsWrapper.class));
    return service;
  }

  private static PayslipDocumentRequest request() {
    return PayslipDocumentRequest.builder()
        .shopName("Sharma Stores")
        .shopAddress("12 MG Road, Pune")
        .employeeName("Asha")
        .employeeCode("EMP-001")
        .designation("Helper")
        .periodLabel("April 2025")
        .dayRows(List.of(new PayslipDocumentRequest.Row("Present", "24"),
            new PayslipDocumentRequest.Row("Paid days", "28 of 30")))
        .earnings(List.of(new PayslipDocumentRequest.Item("Basic pay",
            new BigDecimal("135333.33"))))
        .deductions(List.of(new PayslipDocumentRequest.Item("Advance recovery",
            new BigDecimal("1000"))))
        .grossPay(new BigDecimal("135333.33"))
        .totalDeductions(new BigDecimal("1000"))
        .netBeforeRounding(new BigDecimal("134333.33"))
        .rounding(new BigDecimal("-0.33"))
        .netPay(new BigDecimal("134333"))
        .netPayInWords("One Lakh Thirty Four Thousands Three Hundred and Thirty Three Rupees only")
        .advanceBalance(new BigDecimal("2000"))
        .generatedAtLabel("Generated 03 May 2025, 10:15")
        .build();
  }

  @Test
  void theHtmlShowsShopEmployeeDaysPayAndWords() {
    String html = service().renderHtml(request());

    for (String expected : List.of("Sharma Stores", "12 MG Road, Pune", "Asha", "EMP-001",
        "Helper", "Payslip for April 2025", "28 of 30", "Basic pay", "Rs. 1,35,333.33",
        "Advance recovery", "Rs. 1,34,333.00", "Rs. -0.33", "Advance balance still owed",
        "Rs. 2,000.00", "One Lakh Thirty Four Thousands")) {
      assertTrue(html.contains(expected), "missing " + expected);
    }
  }

  @Test
  void noAddressNoRoundingAndNoAdvanceAreLeftOut() {
    PayslipDocumentRequest r = request();
    r.setShopAddress(null);
    r.setRounding(BigDecimal.ZERO);
    r.setAdvanceBalance(null);

    String html = service().renderHtml(r);

    assertFalse(html.contains("Pay before rounding"));
    assertFalse(html.contains("Advance balance still owed"));
    assertFalse(html.contains("company-details\">"));
  }

  @Test
  void itRendersAPdf() {
    byte[] pdf = service().generate(request());

    assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
  }

  @Test
  void moneyUsesIndianGrouping() {
    assertEquals("Rs. 12,34,567.50", PayslipPdfService.money(new BigDecimal("1234567.5")));
    assertEquals("", PayslipPdfService.money(null));
  }
}
