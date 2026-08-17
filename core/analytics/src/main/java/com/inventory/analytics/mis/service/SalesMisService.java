package com.inventory.analytics.mis.service;

import com.inventory.analytics.mis.rest.dto.MisSalesReportResponse;
import com.inventory.analytics.mis.rest.dto.MisSalesRowDto;
import com.inventory.analytics.mis.rest.dto.MisSalesSummaryDto;
import com.inventory.analytics.mis.support.MisDateRangeHelper;
import com.inventory.analytics.mis.support.MisMoneyTenderHelper;
import com.inventory.analytics.mis.support.MisReportSupport;
import com.inventory.analytics.mis.support.MisTabularDocumentFactory;
import com.inventory.analytics.utils.constants.AnalyticsMetricsConstants;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import com.inventory.documentservice.service.DocumentService;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.service.MisProductQueryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalesMisService {

  private final MisProductQueryService misProductQueryService;
  private final DocumentService documentService;
  private final MetricsWrapper metrics;

  public MisSalesReportResponse getReport(
      String shopId,
      LocalDate fromIn,
      LocalDate toIn,
      String paymentMethod,
      String customerId,
      String q,
      Integer page,
      Integer size) {
    metrics.record(
        AnalyticsMetricsConstants.MIS_REPORTS_TOTAL,
        1,
        "module",
        AnalyticsMetricsConstants.MODULE,
        "operation",
        "sales");
    BuiltReport built = build(shopId, fromIn, toIn, paymentMethod, customerId, q);
    int p = MisReportSupport.safePage(page);
    int s = MisReportSupport.safeSize(size);
    return MisSalesReportResponse.builder()
        .from(built.from())
        .to(built.to())
        .summary(built.summary())
        .rows(MisReportSupport.paginate(built.rows(), p, s))
        .page(p)
        .size(s)
        .totalItems(built.rows().size())
        .build();
  }

  public BuiltReport buildReport(
      String shopId,
      LocalDate fromIn,
      LocalDate toIn,
      String paymentMethod,
      String customerId,
      String q) {
    return build(shopId, fromIn, toIn, paymentMethod, customerId, q);
  }

  public byte[] exportExcel(
      String shopId,
      String shopName,
      LocalDate fromIn,
      LocalDate toIn,
      String paymentMethod,
      String customerId,
      String q) {
    BuiltReport built = build(shopId, fromIn, toIn, paymentMethod, customerId, q);
    MisReportSupport.assertExportSize(built.rows().size());
    MisTabularDocumentRequest doc =
        MisTabularDocumentFactory.salesReport(
            "Sales MIS",
            shopName,
            LocalDateTime.now(),
            built.from() + " to " + built.to(),
            built.summary(),
            built.rows());
    return documentService.generateMisExcel(doc);
  }

  public byte[] exportPdf(
      String shopId,
      String shopName,
      LocalDate fromIn,
      LocalDate toIn,
      String paymentMethod,
      String customerId,
      String q) {
    BuiltReport built = build(shopId, fromIn, toIn, paymentMethod, customerId, q);
    MisReportSupport.assertExportSize(built.rows().size());
    MisTabularDocumentRequest doc =
        MisTabularDocumentFactory.salesReport(
            "Sales MIS",
            shopName,
            LocalDateTime.now(),
            built.from() + " to " + built.to(),
            built.summary(),
            built.rows());
    return documentService.generateMisPdf(doc);
  }

  private BuiltReport build(
      String shopId,
      LocalDate fromIn,
      LocalDate toIn,
      String paymentMethodFilter,
      String customerIdFilter,
      String q) {
    LocalDate from = MisDateRangeHelper.resolveFrom(fromIn);
    LocalDate to = MisDateRangeHelper.resolveTo(toIn);
    if (to.isBefore(from)) {
      LocalDate swap = from;
      from = to;
      to = swap;
    }
    Instant fromInst = MisDateRangeHelper.startOfDay(from);
    Instant toInst = MisDateRangeHelper.endOfDay(to);

    String methodFilter =
        StringUtils.hasText(paymentMethodFilter)
            ? paymentMethodFilter.trim().toUpperCase(Locale.ROOT)
            : null;
    String customerFilter =
        StringUtils.hasText(customerIdFilter) ? customerIdFilter.trim() : null;
    String query = StringUtils.hasText(q) ? q.trim().toLowerCase(Locale.ROOT) : null;

    List<Purchase> sales =
        misProductQueryService.findCompletedSalesBySoldAt(shopId, fromInst, toInst);
    List<Refund> refunds =
        misProductQueryService.findRefundsByCreatedAt(shopId, fromInst, toInst);

    Map<String, String> customerNames = Map.of();
    if (query != null) {
      Set<String> customerIds = new HashSet<>();
      for (Purchase p : sales) {
        if (StringUtils.hasText(p.getCustomerId())) {
          customerIds.add(p.getCustomerId().trim());
        }
      }
      customerNames = misProductQueryService.resolveCustomerNames(customerIds);
    }

    Map<LocalDate, DayBucket> byDay = new TreeMap<>();
    BigDecimal gross = BigDecimal.ZERO;
    BigDecimal tax = BigDecimal.ZERO;
    BigDecimal discount = BigDecimal.ZERO;
    BigDecimal cashTotal = BigDecimal.ZERO;
    BigDecimal onlineTotal = BigDecimal.ZERO;
    BigDecimal creditTotal = BigDecimal.ZERO;
    BigDecimal profit = BigDecimal.ZERO;
    long orderCount = 0;

    for (Purchase sale : sales) {
      if (customerFilter != null && !customerFilter.equals(trim(sale.getCustomerId()))) {
        continue;
      }
      if (methodFilter != null) {
        String pm =
            StringUtils.hasText(sale.getPaymentMethod())
                ? sale.getPaymentMethod().trim().toUpperCase(Locale.ROOT)
                : "";
        if (!methodFilter.equals(pm)) {
          continue;
        }
      }
      if (query != null && !saleMatchesQuery(sale, customerNames, query)) {
        continue;
      }

      LocalDate date =
          MisDateRangeHelper.toLocalDate(
              sale.getSoldAt() != null ? sale.getSoldAt() : sale.getCreatedAt());
      if (date == null) {
        continue;
      }

      DayBucket bucket = byDay.computeIfAbsent(date, DayBucket::new);
      bucket.addSale(sale);
      orderCount++;
      gross = gross.add(MisMoneyTenderHelper.nz(sale.getGrandTotal()));
      tax = tax.add(MisMoneyTenderHelper.nz(sale.getTaxTotal()));
      discount =
          discount.add(
              MisMoneyTenderHelper.nz(sale.getDiscountTotal())
                  .add(MisMoneyTenderHelper.nz(sale.getSaleAdditionalDiscountTotal())));
      cashTotal = cashTotal.add(MisMoneyTenderHelper.nz(sale.getCashAmount()));
      onlineTotal = onlineTotal.add(MisMoneyTenderHelper.nz(sale.getOnlineAmount()));
      creditTotal = creditTotal.add(MisMoneyTenderHelper.nz(sale.getCreditAmount()));
      profit = profit.add(MisMoneyTenderHelper.nz(sale.getTotalProfit()));
    }

    Map<String, Purchase> saleById = new HashMap<>();
    for (Purchase p : sales) {
      if (p.getId() != null) {
        saleById.put(p.getId(), p);
      }
    }

    long refundCount = 0;
    BigDecimal refundAmount = BigDecimal.ZERO;
    for (Refund refund : refunds) {
      if (customerFilter != null) {
        String cid = trim(refund.getCustomerId());
        if (cid == null) {
          Purchase against = saleById.get(refund.getPurchaseId());
          cid = against != null ? trim(against.getCustomerId()) : null;
        }
        if (!customerFilter.equals(cid)) {
          continue;
        }
      }
      if (query != null) {
        String hay =
            (nullToEmpty(refund.getCreditNoteNo())
                    + " "
                    + nullToEmpty(refund.getId())
                    + " "
                    + nullToEmpty(refund.getPurchaseId()))
                .toLowerCase(Locale.ROOT);
        if (!hay.contains(query)) {
          continue;
        }
      }
      LocalDate date = MisDateRangeHelper.toLocalDate(refund.getCreatedAt());
      if (date == null) {
        continue;
      }
      DayBucket bucket = byDay.computeIfAbsent(date, DayBucket::new);
      bucket.addRefund(refund);
      refundCount++;
      refundAmount = refundAmount.add(MisMoneyTenderHelper.nz(refund.getRefundAmount()));
    }

    List<MisSalesRowDto> rows = new ArrayList<>();
    for (DayBucket bucket : byDay.values()) {
      rows.add(bucket.toRow());
    }

    BigDecimal grossNz = MisMoneyTenderHelper.nz(gross);
    BigDecimal aov =
        orderCount > 0
            ? grossNz.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2);
    BigDecimal refundNz = MisMoneyTenderHelper.nz(refundAmount);
    BigDecimal netSales = MisMoneyTenderHelper.nz(grossNz.subtract(refundNz));

    MisSalesSummaryDto summary =
        MisSalesSummaryDto.builder()
            .count(orderCount)
            .gross(grossNz)
            .tax(MisMoneyTenderHelper.nz(tax))
            .discount(MisMoneyTenderHelper.nz(discount))
            .cashTotal(MisMoneyTenderHelper.nz(cashTotal))
            .onlineTotal(MisMoneyTenderHelper.nz(onlineTotal))
            .creditTotal(MisMoneyTenderHelper.nz(creditTotal))
            .profit(MisMoneyTenderHelper.nz(profit))
            .aov(aov)
            .refundCount(refundCount)
            .refundAmount(refundNz)
            .netSales(netSales)
            .build();

    return new BuiltReport(from, to, summary, rows);
  }

  private static boolean saleMatchesQuery(
      Purchase sale, Map<String, String> customerNames, String query) {
    String customerName = resolveCustomerName(sale, customerNames);
    String hay =
        (nullToEmpty(sale.getInvoiceNo())
                + " "
                + nullToEmpty(customerName)
                + " "
                + nullToEmpty(sale.getCustomerId())
                + " "
                + nullToEmpty(sale.getId())
                + " "
                + nullToEmpty(sale.getPaymentMethod()))
            .toLowerCase(Locale.ROOT);
    return hay.contains(query);
  }

  private static String resolveCustomerName(Purchase sale, Map<String, String> names) {
    if (StringUtils.hasText(sale.getCustomerId())) {
      String fromMap = names.get(sale.getCustomerId().trim());
      if (StringUtils.hasText(fromMap)) {
        return fromMap;
      }
    }
    if (StringUtils.hasText(sale.getCustomerName())) {
      return sale.getCustomerName().trim();
    }
    return "Walk-in";
  }

  private static String trim(String s) {
    return StringUtils.hasText(s) ? s.trim() : null;
  }

  private static String nullToEmpty(String s) {
    return s != null ? s : "";
  }

  public record BuiltReport(
      LocalDate from, LocalDate to, MisSalesSummaryDto summary, List<MisSalesRowDto> rows) {}

  private static final class DayBucket {
    private final LocalDate date;
    private long orderCount;
    private BigDecimal cash = BigDecimal.ZERO;
    private BigDecimal online = BigDecimal.ZERO;
    private BigDecimal credit = BigDecimal.ZERO;
    private BigDecimal subTotal = BigDecimal.ZERO;
    private BigDecimal tax = BigDecimal.ZERO;
    private BigDecimal discount = BigDecimal.ZERO;
    private BigDecimal grandTotal = BigDecimal.ZERO;
    private BigDecimal cost = BigDecimal.ZERO;
    private BigDecimal profit = BigDecimal.ZERO;
    private long refundCount;
    private BigDecimal refundAmount = BigDecimal.ZERO;

    private DayBucket(LocalDate date) {
      this.date = date;
    }

    private void addSale(Purchase sale) {
      orderCount++;
      cash = cash.add(MisMoneyTenderHelper.nz(sale.getCashAmount()));
      online = online.add(MisMoneyTenderHelper.nz(sale.getOnlineAmount()));
      credit = credit.add(MisMoneyTenderHelper.nz(sale.getCreditAmount()));
      subTotal = subTotal.add(MisMoneyTenderHelper.nz(sale.getSubTotal()));
      tax = tax.add(MisMoneyTenderHelper.nz(sale.getTaxTotal()));
      discount =
          discount.add(
              MisMoneyTenderHelper.nz(sale.getDiscountTotal())
                  .add(MisMoneyTenderHelper.nz(sale.getSaleAdditionalDiscountTotal())));
      grandTotal = grandTotal.add(MisMoneyTenderHelper.nz(sale.getGrandTotal()));
      cost = cost.add(MisMoneyTenderHelper.nz(sale.getTotalCost()));
      profit = profit.add(MisMoneyTenderHelper.nz(sale.getTotalProfit()));
    }

    private void addRefund(Refund refund) {
      refundCount++;
      refundAmount = refundAmount.add(MisMoneyTenderHelper.nz(refund.getRefundAmount()));
    }

    private MisSalesRowDto toRow() {
      BigDecimal grossNz = MisMoneyTenderHelper.nz(grandTotal);
      BigDecimal profitNz = MisMoneyTenderHelper.nz(profit);
      BigDecimal refundNz = MisMoneyTenderHelper.nz(refundAmount);
      BigDecimal margin =
          grossNz.signum() > 0
              ? profitNz
                  .multiply(BigDecimal.valueOf(100))
                  .divide(grossNz, 2, RoundingMode.HALF_UP)
              : BigDecimal.ZERO.setScale(2);
      return MisSalesRowDto.builder()
          .date(date)
          .orderCount(orderCount)
          .cash(MisMoneyTenderHelper.nz(cash))
          .online(MisMoneyTenderHelper.nz(online))
          .credit(MisMoneyTenderHelper.nz(credit))
          .subTotal(MisMoneyTenderHelper.nz(subTotal))
          .tax(MisMoneyTenderHelper.nz(tax))
          .discount(MisMoneyTenderHelper.nz(discount))
          .grandTotal(grossNz)
          .cost(MisMoneyTenderHelper.nz(cost))
          .profit(profitNz)
          .margin(margin)
          .refundCount(refundCount)
          .refundAmount(refundNz)
          .netSales(MisMoneyTenderHelper.nz(grossNz.subtract(refundNz)))
          .build();
    }
  }
}
