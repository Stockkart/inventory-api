package com.inventory.analytics.mis.support;

import com.inventory.analytics.mis.rest.dto.MisBankSummaryRowDto;
import com.inventory.analytics.mis.rest.dto.MisBankSummaryTotalsDto;
import com.inventory.analytics.mis.rest.dto.MisMoneyPartySummaryDto;
import com.inventory.analytics.mis.rest.dto.MisMoneyRowDto;
import com.inventory.analytics.mis.rest.dto.MisMoneySummaryDto;
import com.inventory.analytics.mis.rest.dto.MisSalesRowDto;
import com.inventory.analytics.mis.rest.dto.MisSalesSummaryDto;
import com.inventory.analytics.mis.rest.dto.MisStockRowDto;
import com.inventory.analytics.mis.rest.dto.MisStockSummaryDto;
import com.inventory.documentservice.rest.dto.mis.MisDocumentKpi;
import com.inventory.documentservice.rest.dto.mis.MisDocumentSheet;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
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

  public static MisTabularDocumentRequest customerMisReport(
      String shopName,
      LocalDateTime generatedAt,
      String periodLabel,
      MisMoneySummaryDto moneySummary,
      List<MisMoneyRowDto> moneyRows,
      MisSalesSummaryDto salesSummary,
      List<MisSalesRowDto> salesRows) {
    MisTabularDocumentRequest money =
        moneyReport(
            "Customer MIS",
            shopName,
            generatedAt,
            periodLabel,
            moneySummary,
            moneyRows,
            false);

    List<MisDocumentKpi> kpis = new ArrayList<>();
    if (money.getKpis() != null) {
      kpis.addAll(money.getKpis());
    }
    kpis.addAll(prefixedSalesKpis(salesSummary));

    List<MisDocumentSheet> extra = new ArrayList<>();
    extra.add(
        MisDocumentSheet.builder()
            .title("Sales")
            .columns(salesDailyColumns())
            .rows(salesDailyRows(salesRows))
            .build());
    extra.add(
        MisDocumentSheet.builder()
            .title("By party")
            .columns(money.getSecondaryColumns())
            .rows(money.getSecondaryRows())
            .build());

    return MisTabularDocumentRequest.builder()
        .title("Customer MIS")
        .shopName(shopName)
        .periodLabel(periodLabel)
        .generatedAtLabel(generatedAt != null ? TS.format(generatedAt) : "")
        .kpis(kpis)
        .detailSheetTitle("Customer money")
        .columns(money.getColumns())
        .rows(money.getRows())
        .extraSheets(extra)
        .build();
  }

  public static MisTabularDocumentRequest salesReport(
      String title,
      String shopName,
      LocalDateTime generatedAt,
      String periodLabel,
      MisSalesSummaryDto summary,
      List<MisSalesRowDto> rows) {
    return MisTabularDocumentRequest.builder()
        .title(title)
        .shopName(shopName)
        .periodLabel(periodLabel)
        .generatedAtLabel(generatedAt != null ? TS.format(generatedAt) : "")
        .kpis(salesKpis(summary))
        .detailSheetTitle("Sales")
        .columns(salesDailyColumns())
        .rows(salesDailyRows(rows))
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

  private static List<MisDocumentKpi> salesKpis(MisSalesSummaryDto summary) {
    List<MisDocumentKpi> kpis = new ArrayList<>();
    if (summary == null) {
      return kpis;
    }
    kpis.add(kpi("Orders", String.valueOf(summary.getCount())));
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
    return kpis;
  }

  private static List<MisDocumentKpi> prefixedSalesKpis(MisSalesSummaryDto summary) {
    List<MisDocumentKpi> kpis = new ArrayList<>();
    for (MisDocumentKpi item : salesKpis(summary)) {
      kpis.add(kpi("Sales - " + item.getLabel(), item.getValue()));
    }
    return kpis;
  }

  private static List<String> salesDailyColumns() {
    return List.of(
        "Date", "Orders", "Gross", "Tax", "Cash", "Online", "Credit", "Profit", "Net sales");
  }

  private static List<List<String>> salesDailyRows(List<MisSalesRowDto> rows) {
    List<List<String>> table = new ArrayList<>();
    if (rows == null) {
      return table;
    }
    for (MisSalesRowDto r : rows) {
      table.add(
          List.of(
              MisReportSupport.formatDate(r.getDate()),
              String.valueOf(r.getOrderCount()),
              MisReportSupport.rupee(r.getGrandTotal()),
              MisReportSupport.rupee(r.getTax()),
              MisReportSupport.rupee(r.getCash()),
              MisReportSupport.rupee(r.getOnline()),
              MisReportSupport.rupee(r.getCredit()),
              MisReportSupport.rupee(r.getProfit()),
              MisReportSupport.rupee(r.getNetSales())));
    }
    return table;
  }

  private static MisDocumentKpi kpi(String label, String value) {
    return MisDocumentKpi.builder().label(label).value(value).build();
  }

  private static String nullToEmpty(String s) {
    return s != null ? s : "";
  }

  /**
   * Company-wise opening / purchase / sale / closing stock value.
   *
   * <p>The adjustment column is only emitted when something actually moved through it, so
   * the ordinary export keeps the four columns the shop's old report printed. TOTAL rides
   * along as a final ordinary row, since the payload has no notion of a total.
   */
  public static MisTabularDocumentRequest bankSummaryReport(
      String title,
      String shopName,
      LocalDateTime generatedAt,
      LocalDate from,
      LocalDate to,
      MisBankSummaryTotalsDto totals,
      List<MisBankSummaryRowDto> rows,
      boolean includeAdjustment) {

    List<MisDocumentKpi> kpis = new ArrayList<>();
    if (totals != null) {
      kpis.add(kpi("Companies", String.valueOf(totals.getCompanyCount())));
      kpis.add(kpi("Opening", MisReportSupport.rupee(totals.getOpening())));
      kpis.add(kpi("Purchase", MisReportSupport.rupee(totals.getPurchase())));
      kpis.add(kpi("Sale", MisReportSupport.rupee(totals.getSale())));
      if (includeAdjustment) {
        kpis.add(kpi("Adjustment", MisReportSupport.rupee(totals.getAdjustment())));
      }
      kpis.add(kpi("Closing", MisReportSupport.rupee(totals.getClosing())));
    }

    List<String> columns = new ArrayList<>(List.of("Company", "Opening", "Purchase", "Sale"));
    if (includeAdjustment) {
      columns.add("Adjustment");
    }
    columns.add("Closing");

    List<List<String>> table = new ArrayList<>();
    if (rows != null) {
      for (MisBankSummaryRowDto r : rows) {
        table.add(bankSummaryRow(nullToEmpty(r.getCompany()), r.getOpening(), r.getPurchase(),
            r.getSale(), r.getAdjustment(), r.getClosing(), includeAdjustment));
      }
    }
    if (totals != null) {
      table.add(bankSummaryRow("TOTAL", totals.getOpening(), totals.getPurchase(),
          totals.getSale(), totals.getAdjustment(), totals.getClosing(), includeAdjustment));
    }

    return MisTabularDocumentRequest.builder()
        .title(title)
        .shopName(shopName)
        .periodLabel(MisReportSupport.formatDate(from) + " - " + MisReportSupport.formatDate(to))
        .generatedAtLabel(generatedAt != null ? TS.format(generatedAt) : "")
        .kpis(kpis)
        .columns(columns)
        .rows(table)
        .detailSheetTitle("Bank Summary")
        .build();
  }

  private static List<String> bankSummaryRow(
      String label,
      BigDecimal opening,
      BigDecimal purchase,
      BigDecimal sale,
      BigDecimal adjustment,
      BigDecimal closing,
      boolean includeAdjustment) {
    List<String> cells = new ArrayList<>();
    cells.add(label);
    cells.add(MisReportSupport.rupee(opening));
    cells.add(MisReportSupport.rupee(purchase));
    cells.add(MisReportSupport.rupee(sale));
    if (includeAdjustment) {
      cells.add(MisReportSupport.rupee(adjustment));
    }
    cells.add(MisReportSupport.rupee(closing));
    return cells;
  }
}
