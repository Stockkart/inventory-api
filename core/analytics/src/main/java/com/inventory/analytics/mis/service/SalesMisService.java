package com.inventory.analytics.mis.service;

import com.inventory.analytics.mis.rest.dto.MisSalesReportResponse;
import com.inventory.analytics.mis.rest.dto.MisSalesRowDto;
import com.inventory.analytics.mis.rest.dto.MisSalesSummaryDto;
import com.inventory.analytics.mis.support.MisDateRangeHelper;
import com.inventory.analytics.mis.support.MisMoneyTenderHelper;
import com.inventory.analytics.mis.support.MisReportSupport;
import com.inventory.analytics.mis.support.MisTabularDocumentFactory;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import com.inventory.documentservice.service.DocumentService;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.service.MisProductQueryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

  public MisSalesReportResponse getReport(
      String shopId,
      LocalDate fromIn,
      LocalDate toIn,
      String paymentMethod,
      String customerId,
      String q,
      Integer page,
      Integer size) {
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

    Set<String> customerIds = new HashSet<>();
    for (Purchase p : sales) {
      if (StringUtils.hasText(p.getCustomerId())) {
        customerIds.add(p.getCustomerId().trim());
      }
    }
    Map<String, String> customerNames = misProductQueryService.resolveCustomerNames(customerIds);

    List<MisSalesRowDto> rows = new ArrayList<>();
    BigDecimal gross = BigDecimal.ZERO;
    BigDecimal tax = BigDecimal.ZERO;
    BigDecimal discount = BigDecimal.ZERO;
    BigDecimal cashTotal = BigDecimal.ZERO;
    BigDecimal onlineTotal = BigDecimal.ZERO;
    BigDecimal creditTotal = BigDecimal.ZERO;
    BigDecimal profit = BigDecimal.ZERO;

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

      String customerName = resolveCustomerName(sale, customerNames);
      MisSalesRowDto row = toRow(sale, customerName);
      if (query != null && !matchesQuery(row, query)) {
        continue;
      }
      rows.add(row);

      gross = gross.add(MisMoneyTenderHelper.nz(row.getGrandTotal()));
      tax = tax.add(MisMoneyTenderHelper.nz(row.getTax()));
      discount = discount.add(MisMoneyTenderHelper.nz(row.getDiscount()));
      cashTotal = cashTotal.add(MisMoneyTenderHelper.nz(row.getCash()));
      onlineTotal = onlineTotal.add(MisMoneyTenderHelper.nz(row.getOnline()));
      creditTotal = creditTotal.add(MisMoneyTenderHelper.nz(row.getCredit()));
      profit = profit.add(MisMoneyTenderHelper.nz(row.getProfit()));
    }

    rows.sort(
        Comparator.comparing(MisSalesRowDto::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                MisSalesRowDto::getInvoiceNo, Comparator.nullsLast(String::compareToIgnoreCase))
            .thenComparing(
                MisSalesRowDto::getSaleId, Comparator.nullsLast(Comparator.naturalOrder())));

    // Refund KPIs: filter by customer when set; paymentMethod does not apply to refunds.
    long refundCount = 0;
    BigDecimal refundAmount = BigDecimal.ZERO;
    Map<String, Purchase> saleById = new HashMap<>();
    for (Purchase p : sales) {
      if (p.getId() != null) {
        saleById.put(p.getId(), p);
      }
    }
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
      refundCount++;
      refundAmount = refundAmount.add(MisMoneyTenderHelper.nz(refund.getRefundAmount()));
    }

    long count = rows.size();
    BigDecimal grossNz = MisMoneyTenderHelper.nz(gross);
    BigDecimal aov =
        count > 0
            ? grossNz.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2);
    BigDecimal refundNz = MisMoneyTenderHelper.nz(refundAmount);
    BigDecimal netSales = MisMoneyTenderHelper.nz(grossNz.subtract(refundNz));

    MisSalesSummaryDto summary =
        MisSalesSummaryDto.builder()
            .count(count)
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

  private static MisSalesRowDto toRow(Purchase sale, String customerName) {
    BigDecimal discount =
        MisMoneyTenderHelper.nz(sale.getDiscountTotal())
            .add(MisMoneyTenderHelper.nz(sale.getSaleAdditionalDiscountTotal()));
    return MisSalesRowDto.builder()
        .saleId(sale.getId())
        .date(
            MisDateRangeHelper.toLocalDate(
                sale.getSoldAt() != null ? sale.getSoldAt() : sale.getCreatedAt()))
        .invoiceNo(sale.getInvoiceNo())
        .customerId(trim(sale.getCustomerId()))
        .customer(customerName)
        .paymentMethod(sale.getPaymentMethod())
        .cash(MisMoneyTenderHelper.nz(sale.getCashAmount()))
        .online(MisMoneyTenderHelper.nz(sale.getOnlineAmount()))
        .credit(MisMoneyTenderHelper.nz(sale.getCreditAmount()))
        .subTotal(MisMoneyTenderHelper.nz(sale.getSubTotal()))
        .tax(MisMoneyTenderHelper.nz(sale.getTaxTotal()))
        .discount(discount)
        .grandTotal(MisMoneyTenderHelper.nz(sale.getGrandTotal()))
        .cost(MisMoneyTenderHelper.nz(sale.getTotalCost()))
        .profit(MisMoneyTenderHelper.nz(sale.getTotalProfit()))
        .margin(MisMoneyTenderHelper.nz(sale.getMarginPercent()))
        .build();
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

  private static boolean matchesQuery(MisSalesRowDto row, String query) {
    String hay =
        (nullToEmpty(row.getInvoiceNo())
                + " "
                + nullToEmpty(row.getCustomer())
                + " "
                + nullToEmpty(row.getCustomerId())
                + " "
                + nullToEmpty(row.getSaleId())
                + " "
                + nullToEmpty(row.getPaymentMethod()))
            .toLowerCase(Locale.ROOT);
    return hay.contains(query);
  }

  private static String trim(String s) {
    return StringUtils.hasText(s) ? s.trim() : null;
  }

  private static String nullToEmpty(String s) {
    return s != null ? s : "";
  }

  private record BuiltReport(
      LocalDate from, LocalDate to, MisSalesSummaryDto summary, List<MisSalesRowDto> rows) {}
}
