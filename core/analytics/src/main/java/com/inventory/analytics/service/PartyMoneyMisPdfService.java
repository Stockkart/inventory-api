package com.inventory.analytics.service;

import com.inventory.analytics.rest.dto.response.PartyMoneyMisResponse;
import com.inventory.analytics.rest.dto.response.PartyMoneyMisRowDto;
import com.inventory.analytics.rest.dto.response.PartyMoneyMisSummaryDto;
import com.inventory.documentservice.service.DocumentService;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Renders Vendor Money MIS as a landscape PDF via DocumentService (same as scan-sell invoices). */
@Component
@RequiredArgsConstructor
public class PartyMoneyMisPdfService {

  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
  private static final NumberFormat MONEY =
      NumberFormat.getCurrencyInstance(new Locale("en", "IN"));

  private final DocumentService documentService;

  public byte[] generate(PartyMoneyMisResponse report, String shopName) {
    String html = buildHtml(report, shopName);
    return documentService.generatePdfFromHtml(html);
  }

  private String buildHtml(PartyMoneyMisResponse report, String shopName) {
    StringBuilder sb = new StringBuilder();
    sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'/>")
        .append("<style>")
        .append("@page { size: A4 landscape; margin: 12mm; }")
        .append("body{font-family:Arial,sans-serif;font-size:10px;color:#111;}")
        .append("h1{font-size:16px;margin:0 0 4px 0;}")
        .append("p.meta{margin:0 0 10px 0;color:#444;}")
        .append("table{width:100%;border-collapse:collapse;}")
        .append("th,td{border:1px solid #333;padding:4px 6px;text-align:left;}")
        .append("th{background:#eee;}")
        .append("td.num{text-align:right;}")
        .append(".summary{margin-top:12px;}")
        .append("</style></head><body>");
    sb.append("<h1>")
        .append(escape(shopName != null ? shopName : "Shop"))
        .append(" — Vendor Money MIS</h1>");
    sb.append("<p class='meta'>Period: ")
        .append(report.getFrom() != null ? report.getFrom().format(DATE_FMT) : "")
        .append(" – ")
        .append(report.getTo() != null ? report.getTo().format(DATE_FMT) : "")
        .append(" · Timezone Asia/Kolkata</p>");

    sb.append("<table><thead><tr>")
        .append("<th>Date</th><th>Party</th><th>Txn ID</th><th>Type</th><th>Ref No</th>")
        .append("<th>Against</th><th>Total</th><th>Cash</th><th>Online</th><th>Credit</th>")
        .append("<th>Balance</th></tr></thead><tbody>");

    for (PartyMoneyMisRowDto row : report.getRows()) {
      sb.append("<tr>")
          .append("<td>")
          .append(row.getTxnDate() != null ? row.getTxnDate().format(DATE_FMT) : "")
          .append("</td>")
          .append("<td>")
          .append(escape(row.getPartyName()))
          .append("</td>")
          .append("<td>")
          .append(escape(row.getTxnId()))
          .append("</td>")
          .append("<td>")
          .append(escape(row.getTxnTypeLabel()))
          .append("</td>")
          .append("<td>")
          .append(escape(row.getRefNo()))
          .append("</td>")
          .append("<td>")
          .append(escape(row.getAgainstRefNo()))
          .append("</td>")
          .append("<td class='num'>")
          .append(fmt(row.getTotalAmount()))
          .append("</td>")
          .append("<td class='num'>")
          .append(fmt(row.getCashAmount()))
          .append("</td>")
          .append("<td class='num'>")
          .append(fmt(row.getOnlineAmount()))
          .append("</td>")
          .append("<td class='num'>")
          .append(fmt(row.getCreditAmount()))
          .append("</td>")
          .append("<td class='num'>")
          .append(fmt(row.getBalanceAfter()))
          .append("</td>")
          .append("</tr>");
    }
    sb.append("</tbody></table>");

    PartyMoneyMisSummaryDto s = report.getSummary();
    if (s != null) {
      sb.append("<p class='summary'><strong>Summary</strong> — Cash: ")
          .append(fmt(s.getPeriodCashTotal()))
          .append(" · Online: ")
          .append(fmt(s.getPeriodOnlineTotal()))
          .append(" · Credit: ")
          .append(fmt(s.getPeriodCreditTotal()))
          .append(" · Current payable: ")
          .append(fmt(s.getCurrentPayableTotal()))
          .append("</p>");
    }
    sb.append("</body></html>");
    return sb.toString();
  }

  private static String fmt(BigDecimal v) {
    if (v == null || v.signum() == 0) {
      return "—";
    }
    return MONEY.format(v);
  }

  private static String escape(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
