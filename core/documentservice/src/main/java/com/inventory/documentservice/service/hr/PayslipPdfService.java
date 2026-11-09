package com.inventory.documentservice.service.hr;

import com.inventory.documentservice.rest.dto.hr.PayslipDocumentRequest;
import com.inventory.documentservice.service.HtmlToPdfConverter;
import com.inventory.documentservice.utils.constants.DocumentMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Payslip PDF (HR R10.1–R10.3): Thymeleaf template {@code templates/hr/payslip.html} rendered
 * through {@link HtmlToPdfConverter}, like invoices. One page; nothing is stored.
 */
@Slf4j
@Service
public class PayslipPdfService {

  static final String TEMPLATE = "hr/payslip";

  @Autowired private TemplateEngine templateEngine;

  @Autowired private HtmlToPdfConverter htmlToPdfConverter;

  @Autowired private MetricsWrapper metrics;

  /** @return the PDF bytes */
  public byte[] generate(PayslipDocumentRequest request) {
    try {
      byte[] pdf = htmlToPdfConverter.convert(renderHtml(request));
      metrics.record(DocumentMetricsConstants.GENERATED_TOTAL, 1,
          "module", DocumentMetricsConstants.MODULE, "operation", "payslip_pdf");
      return pdf;
    } catch (Exception e) {
      // exception class only: a template error can quote the payslip's values
      log.error("Error generating payslip PDF: {}", e.getClass().getSimpleName());
      throw new IllegalStateException("Failed to generate payslip PDF", e);
    }
  }

  /** The payslip as HTML (also what the PDF renders). */
  public String renderHtml(PayslipDocumentRequest request) {
    Context context = new Context();
    context.setVariable("shopName", text(request.getShopName()));
    context.setVariable("shopAddress", request.getShopAddress());
    context.setVariable("employeeName", text(request.getEmployeeName()));
    context.setVariable("employeeCode", text(request.getEmployeeCode()));
    context.setVariable("designation", request.getDesignation());
    context.setVariable("periodLabel", text(request.getPeriodLabel()));
    context.setVariable("dayRows", request.getDayRows() != null ? request.getDayRows() : List.of());
    context.setVariable("earnings", money(request.getEarnings()));
    context.setVariable("deductions", money(request.getDeductions()));
    context.setVariable("grossPay", money(request.getGrossPay()));
    context.setVariable("totalDeductions", money(request.getTotalDeductions()));
    context.setVariable("netBeforeRounding", money(request.getNetBeforeRounding()));
    BigDecimal rounding = request.getRounding();
    context.setVariable("showRounding", rounding != null && rounding.signum() != 0);
    context.setVariable("rounding", rounding != null && rounding.signum() > 0
        ? "+" + money(rounding) : money(rounding));
    context.setVariable("netPay", money(request.getNetPay()));
    context.setVariable("netPayInWords", text(request.getNetPayInWords()));
    context.setVariable("showAdvance", request.getAdvanceBalance() != null);
    context.setVariable("advanceBalance", money(request.getAdvanceBalance()));
    context.setVariable("paymentNote", request.getPaymentNote());
    context.setVariable("generatedAtLabel", text(request.getGeneratedAtLabel()));
    return templateEngine.process(TEMPLATE, context);
  }

  /** "Rs. 1,23,456.00": Indian digit grouping; the PDF's built-in fonts have no rupee sign. */
  static String money(BigDecimal amount) {
    if (amount == null) {
      return "";
    }
    String plain = amount.setScale(2, RoundingMode.HALF_UP).abs().toPlainString();
    String whole = plain.substring(0, plain.length() - 3);
    // last three digits, then groups of two (lakh, crore): 12,34,567
    StringBuilder grouped = new StringBuilder();
    int head = whole.length() > 3 ? whole.length() - 3 : 0;
    String rest = whole.substring(0, head);
    for (int i = 0; i < rest.length(); i++) {
      grouped.append(rest.charAt(i));
      if ((rest.length() - i - 1) % 2 == 0) {
        grouped.append(',');
      }
    }
    grouped.append(whole.substring(head));
    return "Rs. " + (amount.signum() < 0 ? "-" : "") + grouped + plain.substring(plain.length() - 3);
  }

  private static List<String[]> money(List<PayslipDocumentRequest.Item> items) {
    List<String[]> out = new ArrayList<>();
    if (items != null) {
      for (PayslipDocumentRequest.Item i : items) {
        out.add(new String[] {text(i.getLabel()), money(i.getAmount())});
      }
    }
    return out;
  }

  private static String text(String value) {
    return value != null ? value : "";
  }
}
