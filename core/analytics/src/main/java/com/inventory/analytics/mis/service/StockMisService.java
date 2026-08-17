package com.inventory.analytics.mis.service;

import com.inventory.analytics.mis.rest.dto.MisStockReportResponse;
import com.inventory.analytics.mis.rest.dto.MisStockRowDto;
import com.inventory.analytics.mis.rest.dto.MisStockSummaryDto;
import com.inventory.analytics.mis.support.MisMoneyTenderHelper;
import com.inventory.analytics.mis.support.MisReportSupport;
import com.inventory.analytics.mis.support.MisTabularDocumentFactory;
import com.inventory.analytics.utils.constants.AnalyticsMetricsConstants;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import com.inventory.documentservice.service.DocumentService;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.service.MisProductQueryService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockMisService {

  private final MisProductQueryService misProductQueryService;
  private final DocumentService documentService;
  private final MetricsWrapper metrics;

  public MisStockReportResponse getReport(
      String shopId,
      String q,
      Boolean lowStockOnly,
      Boolean deadStockOnly,
      Integer page,
      Integer size) {
    metrics.record(
        AnalyticsMetricsConstants.MIS_REPORTS_TOTAL,
        1,
        "module",
        AnalyticsMetricsConstants.MODULE,
        "operation",
        "stock");
    BuiltReport built = build(shopId, q, lowStockOnly, deadStockOnly);
    int p = MisReportSupport.safePage(page);
    int s = MisReportSupport.safeSize(size);
    return MisStockReportResponse.builder()
        .summary(built.summary())
        .rows(MisReportSupport.paginate(built.rows(), p, s))
        .page(p)
        .size(s)
        .totalItems(built.rows().size())
        .build();
  }

  public byte[] exportExcel(
      String shopId, String shopName, String q, Boolean lowStockOnly, Boolean deadStockOnly) {
    BuiltReport built = build(shopId, q, lowStockOnly, deadStockOnly);
    MisReportSupport.assertExportSize(built.rows().size());
    MisTabularDocumentRequest doc =
        MisTabularDocumentFactory.stockReport(
            "Stock MIS", shopName, LocalDateTime.now(), built.summary(), built.rows());
    return documentService.generateMisExcel(doc);
  }

  public byte[] exportPdf(
      String shopId, String shopName, String q, Boolean lowStockOnly, Boolean deadStockOnly) {
    BuiltReport built = build(shopId, q, lowStockOnly, deadStockOnly);
    MisReportSupport.assertExportSize(built.rows().size());
    MisTabularDocumentRequest doc =
        MisTabularDocumentFactory.stockReport(
            "Stock MIS", shopName, LocalDateTime.now(), built.summary(), built.rows());
    return documentService.generateMisPdf(doc);
  }

  private BuiltReport build(
      String shopId, String q, Boolean lowStockOnly, Boolean deadStockOnly) {
    boolean filterLow = Boolean.TRUE.equals(lowStockOnly);
    boolean filterDead = Boolean.TRUE.equals(deadStockOnly);
    String query = StringUtils.hasText(q) ? q.trim().toLowerCase(Locale.ROOT) : null;

    List<Inventory> inventories = misProductQueryService.findAllInventory(shopId);
    List<MisStockRowDto> rows = new ArrayList<>();

    BigDecimal onHandQty = BigDecimal.ZERO;
    BigDecimal costValuation = BigDecimal.ZERO;
    BigDecimal sellValuation = BigDecimal.ZERO;
    BigDecimal potentialProfit = BigDecimal.ZERO;
    long lowStockCount = 0;
    long deadStockCount = 0;

    for (Inventory inv : inventories) {
      MisStockRowDto row = toRow(inv);
      if (filterLow && !row.isLowStock()) {
        continue;
      }
      if (filterDead && !row.isDeadStock()) {
        continue;
      }
      if (query != null && !matchesQuery(row, query)) {
        continue;
      }
      rows.add(row);

      onHandQty = onHandQty.add(MisMoneyTenderHelper.nz(row.getOnHand()));
      costValuation = costValuation.add(MisMoneyTenderHelper.nz(row.getCostValue()));
      sellValuation = sellValuation.add(MisMoneyTenderHelper.nz(row.getSellValue()));
      potentialProfit = potentialProfit.add(MisMoneyTenderHelper.nz(row.getPotentialProfit()));
      if (row.isLowStock()) {
        lowStockCount++;
      }
      if (row.isDeadStock()) {
        deadStockCount++;
      }
    }

    rows.sort(
        Comparator.comparing(
                MisStockRowDto::getName, Comparator.nullsLast(String::compareToIgnoreCase))
            .thenComparing(
                MisStockRowDto::getLotId, Comparator.nullsLast(String::compareToIgnoreCase))
            .thenComparing(
                MisStockRowDto::getInventoryId, Comparator.nullsLast(Comparator.naturalOrder())));

    MisStockSummaryDto summary =
        MisStockSummaryDto.builder()
            .lotCount(rows.size())
            .onHandQty(MisMoneyTenderHelper.nz(onHandQty))
            .costValuation(MisMoneyTenderHelper.nz(costValuation))
            .sellValuation(MisMoneyTenderHelper.nz(sellValuation))
            .potentialProfit(MisMoneyTenderHelper.nz(potentialProfit))
            .lowStockCount(lowStockCount)
            .deadStockCount(deadStockCount)
            .build();

    return new BuiltReport(summary, rows);
  }

  private static MisStockRowDto toRow(Inventory inv) {
    BigDecimal onHand = qty(inv.getCurrentCount(), inv.getCurrentBaseCount());
    BigDecimal sold = qty(inv.getSoldCount(), inv.getSoldBaseCount());
    BigDecimal cost = MisMoneyTenderHelper.nz(inv.getCostPrice());
    BigDecimal sell =
        MisMoneyTenderHelper.nz(
            inv.getSellingPrice() != null ? inv.getSellingPrice() : inv.getPriceToRetail());
    BigDecimal costValue = MisMoneyTenderHelper.nz(cost.multiply(onHand));
    BigDecimal sellValue = MisMoneyTenderHelper.nz(sell.multiply(onHand));
    BigDecimal potential = MisMoneyTenderHelper.nz(sellValue.subtract(costValue));

    Integer threshold = inv.getThresholdCount();
    boolean lowStock =
        threshold != null && onHand.compareTo(BigDecimal.valueOf(threshold.longValue())) <= 0;
    boolean deadStock = onHand.signum() > 0 && sold.signum() == 0;

    return MisStockRowDto.builder()
        .inventoryId(inv.getId())
        .productId(inv.getProductId())
        .name(StringUtils.hasText(inv.getName()) ? inv.getName().trim() : "Product")
        .barcode(inv.getBarcode())
        .lotId(inv.getLotId())
        .onHand(onHand)
        .threshold(threshold)
        .costPrice(cost)
        .sellPrice(sell)
        .costValue(costValue)
        .sellValue(sellValue)
        .potentialProfit(potential)
        .lowStock(lowStock)
        .deadStock(deadStock)
        .soldCount(sold)
        .build();
  }

  private static BigDecimal qty(BigDecimal display, Integer base) {
    if (display != null) {
      return MisMoneyTenderHelper.nz(display);
    }
    if (base != null) {
      return BigDecimal.valueOf(base.longValue()).setScale(2, java.math.RoundingMode.HALF_UP);
    }
    return BigDecimal.ZERO.setScale(2);
  }

  private static boolean matchesQuery(MisStockRowDto row, String query) {
    String hay =
        (nullToEmpty(row.getName())
                + " "
                + nullToEmpty(row.getBarcode())
                + " "
                + nullToEmpty(row.getLotId())
                + " "
                + nullToEmpty(row.getInventoryId())
                + " "
                + nullToEmpty(row.getProductId()))
            .toLowerCase(Locale.ROOT);
    return hay.contains(query);
  }

  private static String nullToEmpty(String s) {
    return s != null ? s : "";
  }

  private record BuiltReport(MisStockSummaryDto summary, List<MisStockRowDto> rows) {}
}
