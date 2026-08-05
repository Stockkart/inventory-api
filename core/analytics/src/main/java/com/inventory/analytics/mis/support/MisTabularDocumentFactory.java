package com.inventory.analytics.mis.support;

import com.inventory.analytics.mis.rest.dto.MisMoneyPartySummaryDto;
import com.inventory.analytics.mis.rest.dto.MisMoneyRowDto;
import com.inventory.analytics.mis.rest.dto.MisMoneySummaryDto;
import com.inventory.analytics.mis.rest.dto.MisSalesRowDto;
import com.inventory.analytics.mis.rest.dto.MisSalesSummaryDto;
import com.inventory.analytics.mis.rest.dto.MisStockRowDto;
import com.inventory.analytics.mis.rest.dto.MisStockSummaryDto;
import com.inventory.documentservice.rest.dto.mis.MisDocumentKpi;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Builds documentservice payloads from money MIS results. */
public final class MisTabularDocumentFactory {

  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private MisTabularDocumentFactory() {}

  public static MisTabularDocumentRequest moneyReport(
      String title,
      String shopName,
      LocalDateTime generatedAt,
      String periodLabel,
      MisMoneySummaryDto summary,
      List<MisMoneyRowDto> rows,
      boolean vendorPerspective) {
    List<MisDocumentKpi> kpis = new ArrayList<>();
    if (summary != null) {
      kpis.add(kpi("Opening", MisReportSupport.money(summary.getOpeningBalanceTotal())));
      kpis.add(
          kpi(
              vendorPerspective ? "Period purchases" : "Period sales",
              MisReportSupport.money(summary.getPeriodPurchaseOrSaleTotal())));
      kpis.add(kpi("Period cash", MisReportSupport.money(summary.getPeriodCashTotal())));
      kpis.add(kpi("Period online", MisReportSupport.money(summary.getPeriodOnlineTotal())));
      kpis.add(kpi("Period credit", MisReportSupport.money(summary.getPeriodCreditTotal())));
      kpis.add(
          kpi(
              vendorPerspective ? "Current payable" : "Current receivable",
              MisReportSupport.money(summary.getCurrentBalanceTotal())));
    }

    List<String> columns =
        List.of(
            "Date",
            vendorPerspective ? "Vendor" : "Customer",
            "Type",
            "Invoice/Ref",
            "Bill amount",
            "Cash",
            "Online",
            "Credit",
            "Outstanding");

    List<List<String>> table = new ArrayList<>();
    if (rows != null) {
      for (MisMoneyRowDto r : rows) {
        table.add(
            List.of(
                r.getTxnDate() != null ? r.getTxnDate().toString() : "",
                nullToEmpty(r.getPartyName()),
                nullToEmpty(r.getTxnTypeLabel()),
                nullToEmpty(r.getRefNo()),
                MisReportSupport.money(r.getTotalAmount()),
                MisReportSupport.money(r.getCashAmount()),
                MisReportSupport.money(r.getOnlineAmount()),
                MisReportSupport.money(r.getCreditAmount()),
                MisReportSupport.money(r.getBalanceAfter())));
      }
    }

    List<String> partyCols =
        List.of("Party", "Opening", "Closing (period)", "Current");
    List<List<String>> partyRows = new ArrayList<>();
    if (summary != null && summary.getPartySummaries() != null) {
      for (MisMoneyPartySummaryDto p : summary.getPartySummaries()) {
        partyRows.add(
            List.of(
                nullToEmpty(p.getPartyName()),
                MisReportSupport.money(p.getOpeningBalance()),
                MisReportSupport.money(p.getClosingBalanceInPeriod()),
                MisReportSupport.money(p.getCurrentBalance())));
      }
    }

    return MisTabularDocumentRequest.builder()
        .title(title)
        .shopName(shopName)
        .periodLabel(periodLabel)
        .generatedAtLabel(generatedAt != null ? TS.format(generatedAt) : "")
        .kpis(kpis)
        .columns(columns)
        .rows(table)
        .secondarySheetTitle("By party")
        .secondaryColumns(partyCols)
        .secondaryRows(partyRows)
        .build();
  }

  public static MisTabularDocumentRequest salesReport(
      String title,
      String shopName,
      LocalDateTime generatedAt,
      String periodLabel,
      MisSalesSummaryDto summary,
      List<MisSalesRowDto> rows) {
    List<MisDocumentKpi> kpis = new ArrayList<>();
    if (summary != null) {
      kpis.add(kpi("Invoices", String.valueOf(summary.getCount())));
      kpis.add(kpi("Gross", MisReportSupport.money(summary.getGross())));
      kpis.add(kpi("Tax", MisReportSupport.money(summary.getTax())));
      kpis.add(kpi("Discount", MisReportSupport.money(summary.getDiscount())));
      kpis.add(kpi("Cash", MisReportSupport.money(summary.getCashTotal())));
      kpis.add(kpi("Online", MisReportSupport.money(summary.getOnlineTotal())));
      kpis.add(kpi("Credit", MisReportSupport.money(summary.getCreditTotal())));
      kpis.add(kpi("Profit", MisReportSupport.money(summary.getProfit())));
      kpis.add(kpi("AOV", MisReportSupport.money(summary.getAov())));
      kpis.add(kpi("Refunds", String.valueOf(summary.getRefundCount())));
      kpis.add(kpi("Refund amount", MisReportSupport.money(summary.getRefundAmount())));
      kpis.add(kpi("Net sales", MisReportSupport.money(summary.getNetSales())));
    }

    List<String> columns =
        List.of(
            "Date",
            "Invoice",
            "Customer",
            "Method",
            "Cash",
            "Online",
            "Credit",
            "Subtotal",
            "Tax",
            "Discount",
            "Grand total",
            "Cost",
            "Profit",
            "Margin %");

    List<List<String>> table = new ArrayList<>();
    if (rows != null) {
      for (MisSalesRowDto r : rows) {
        table.add(
            List.of(
                r.getDate() != null ? r.getDate().toString() : "",
                nullToEmpty(r.getInvoiceNo()),
                nullToEmpty(r.getCustomer()),
                nullToEmpty(r.getPaymentMethod()),
                MisReportSupport.money(r.getCash()),
                MisReportSupport.money(r.getOnline()),
                MisReportSupport.money(r.getCredit()),
                MisReportSupport.money(r.getSubTotal()),
                MisReportSupport.money(r.getTax()),
                MisReportSupport.money(r.getDiscount()),
                MisReportSupport.money(r.getGrandTotal()),
                MisReportSupport.money(r.getCost()),
                MisReportSupport.money(r.getProfit()),
                MisReportSupport.money(r.getMargin())));
      }
    }

    return MisTabularDocumentRequest.builder()
        .title(title)
        .shopName(shopName)
        .periodLabel(periodLabel)
        .generatedAtLabel(generatedAt != null ? TS.format(generatedAt) : "")
        .kpis(kpis)
        .columns(columns)
        .rows(table)
        .build();
  }

  public static MisTabularDocumentRequest stockReport(
      String title,
      String shopName,
      LocalDateTime generatedAt,
      MisStockSummaryDto summary,
      List<MisStockRowDto> rows) {
    List<MisDocumentKpi> kpis = new ArrayList<>();
    if (summary != null) {
      kpis.add(kpi("Lots", String.valueOf(summary.getLotCount())));
      kpis.add(kpi("On hand", MisReportSupport.money(summary.getOnHandQty())));
      kpis.add(kpi("Cost valuation", MisReportSupport.money(summary.getCostValuation())));
      kpis.add(kpi("Sell valuation", MisReportSupport.money(summary.getSellValuation())));
      kpis.add(kpi("Potential profit", MisReportSupport.money(summary.getPotentialProfit())));
      kpis.add(kpi("Low stock", String.valueOf(summary.getLowStockCount())));
      kpis.add(kpi("Dead stock", String.valueOf(summary.getDeadStockCount())));
    }

    List<String> columns =
        List.of(
            "Product",
            "Barcode",
            "Lot",
            "On hand",
            "Threshold",
            "Cost",
            "Sell",
            "Cost value",
            "Sell value",
            "Potential profit",
            "Low",
            "Dead");

    List<List<String>> table = new ArrayList<>();
    if (rows != null) {
      for (MisStockRowDto r : rows) {
        table.add(
            List.of(
                nullToEmpty(r.getName()),
                nullToEmpty(r.getBarcode()),
                nullToEmpty(r.getLotId()),
                MisReportSupport.money(r.getOnHand()),
                r.getThreshold() != null ? String.valueOf(r.getThreshold()) : "",
                MisReportSupport.money(r.getCostPrice()),
                MisReportSupport.money(r.getSellPrice()),
                MisReportSupport.money(r.getCostValue()),
                MisReportSupport.money(r.getSellValue()),
                MisReportSupport.money(r.getPotentialProfit()),
                r.isLowStock() ? "Y" : "",
                r.isDeadStock() ? "Y" : ""));
      }
    }

    return MisTabularDocumentRequest.builder()
        .title(title)
        .shopName(shopName)
        .periodLabel("Live snapshot")
        .generatedAtLabel(generatedAt != null ? TS.format(generatedAt) : "")
        .kpis(kpis)
        .columns(columns)
        .rows(table)
        .build();
  }

  private static MisDocumentKpi kpi(String label, String value) {
    return MisDocumentKpi.builder().label(label).value(value).build();
  }

  private static String nullToEmpty(String s) {
    return s != null ? s : "";
  }
}
