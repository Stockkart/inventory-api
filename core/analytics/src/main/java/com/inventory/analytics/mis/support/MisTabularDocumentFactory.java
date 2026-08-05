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

/** Builds documentservice payloads from MIS results. */
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
      kpis.add(kpi("Opening", MisReportSupport.rupee(summary.getOpeningBalanceTotal())));
      kpis.add(
          kpi(
              vendorPerspective ? "Period purchases" : "Period sales",
              MisReportSupport.rupee(summary.getPeriodPurchaseOrSaleTotal())));
      kpis.add(kpi("Period cash", MisReportSupport.rupee(summary.getPeriodCashTotal())));
      kpis.add(kpi("Period online", MisReportSupport.rupee(summary.getPeriodOnlineTotal())));
      kpis.add(kpi("Period credit", MisReportSupport.rupee(summary.getPeriodCreditTotal())));
      kpis.add(
          kpi(
              vendorPerspective ? "Current payable" : "Current receivable",
              MisReportSupport.rupee(summary.getCurrentBalanceTotal())));
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
                MisReportSupport.formatDate(r.getTxnDate()),
                nullToEmpty(r.getPartyName()),
                nullToEmpty(r.getTxnTypeLabel()),
                nullToEmpty(r.getRefNo()),
                MisReportSupport.rupee(r.getTotalAmount()),
                MisReportSupport.rupee(r.getCashAmount()),
                MisReportSupport.rupee(r.getOnlineAmount()),
                MisReportSupport.rupee(r.getCreditAmount()),
                MisReportSupport.rupee(r.getBalanceAfter())));
      }
    }

    List<String> partyCols = List.of("Party", "Opening", "Closing (period)", "Current");
    List<List<String>> partyRows = new ArrayList<>();
    if (summary != null && summary.getPartySummaries() != null) {
      for (MisMoneyPartySummaryDto p : summary.getPartySummaries()) {
        partyRows.add(
            List.of(
                nullToEmpty(p.getPartyName()),
                MisReportSupport.rupee(p.getOpeningBalance()),
                MisReportSupport.rupee(p.getClosingBalanceInPeriod()),
                MisReportSupport.rupee(p.getCurrentBalance())));
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
      kpis.add(kpi("Gross", MisReportSupport.rupee(summary.getGross())));
      kpis.add(kpi("Tax", MisReportSupport.rupee(summary.getTax())));
      kpis.add(kpi("Discount", MisReportSupport.rupee(summary.getDiscount())));
      kpis.add(kpi("Cash", MisReportSupport.rupee(summary.getCashTotal())));
      kpis.add(kpi("Online", MisReportSupport.rupee(summary.getOnlineTotal())));
      kpis.add(kpi("Credit", MisReportSupport.rupee(summary.getCreditTotal())));
      kpis.add(kpi("Profit", MisReportSupport.rupee(summary.getProfit())));
      kpis.add(kpi("AOV", MisReportSupport.rupee(summary.getAov())));
      kpis.add(kpi("Refunds", String.valueOf(summary.getRefundCount())));
      kpis.add(kpi("Refund amount", MisReportSupport.rupee(summary.getRefundAmount())));
      kpis.add(kpi("Net sales", MisReportSupport.rupee(summary.getNetSales())));
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
                MisReportSupport.formatDate(r.getDate()),
                nullToEmpty(r.getInvoiceNo()),
                nullToEmpty(r.getCustomer()),
                nullToEmpty(r.getPaymentMethod()),
                MisReportSupport.rupee(r.getCash()),
                MisReportSupport.rupee(r.getOnline()),
                MisReportSupport.rupee(r.getCredit()),
                MisReportSupport.rupee(r.getSubTotal()),
                MisReportSupport.rupee(r.getTax()),
                MisReportSupport.rupee(r.getDiscount()),
                MisReportSupport.rupee(r.getGrandTotal()),
                MisReportSupport.rupee(r.getCost()),
                MisReportSupport.rupee(r.getProfit()),
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
      kpis.add(kpi("Cost valuation", MisReportSupport.rupee(summary.getCostValuation())));
      kpis.add(kpi("Sell valuation", MisReportSupport.rupee(summary.getSellValuation())));
      kpis.add(kpi("Potential profit", MisReportSupport.rupee(summary.getPotentialProfit())));
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
                MisReportSupport.rupee(r.getCostPrice()),
                MisReportSupport.rupee(r.getSellPrice()),
                MisReportSupport.rupee(r.getCostValue()),
                MisReportSupport.rupee(r.getSellValue()),
                MisReportSupport.rupee(r.getPotentialProfit()),
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
