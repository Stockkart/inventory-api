package com.inventory.analytics.mis.service;

import com.inventory.analytics.mis.rest.dto.MisBankSummaryReportResponse;
import com.inventory.analytics.mis.rest.dto.MisBankSummaryRowDto;
import com.inventory.analytics.mis.support.BankSummaryEngine;
import com.inventory.analytics.mis.support.MisDateRangeHelper;
import com.inventory.analytics.mis.support.MisReportSupport;
import com.inventory.analytics.mis.support.MisTabularDocumentFactory;
import com.inventory.analytics.utils.constants.AnalyticsMetricsConstants;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import com.inventory.documentservice.service.DocumentService;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.domain.model.StockPeriodSnapshot;
import com.inventory.product.service.MisProductQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Company-wise opening / purchase / sale / closing stock value for a period.
 *
 * <p>Opening comes from the prior period's close when there is one, so the chain the shop
 * cares about -- 31-Jul closing is 01-Aug opening -- holds by construction rather than by
 * recomputation.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankSummaryMisService {

  static final String SOURCE_SNAPSHOT = "SNAPSHOT";
  static final String SOURCE_DERIVED = "DERIVED";

  private final MisProductQueryService misProductQueryService;
  private final DocumentService documentService;
  private final MetricsWrapper metrics;

  public MisBankSummaryReportResponse getReport(
      String shopId, LocalDate from, LocalDate to, String q, Integer page, Integer size) {
    metrics.record(
        AnalyticsMetricsConstants.MIS_REPORTS_TOTAL,
        1,
        "module",
        AnalyticsMetricsConstants.MODULE,
        "operation",
        "bank-summary");

    Built built = build(shopId, from, to, q);
    int p = MisReportSupport.safePage(page);
    int s = MisReportSupport.safeSize(size);

    return MisBankSummaryReportResponse.builder()
        .from(built.from())
        .to(built.to())
        .openingSource(built.openingSource())
        .openingSnapshotDate(built.openingSnapshotDate())
        .hasAdjustments(built.result().hasAdjustments())
        .periodClosed(misProductQueryService.findSnapshot(shopId, built.to()).isPresent())
        .totals(built.result().totals())
        .rows(MisReportSupport.paginate(built.result().rows(), p, s))
        .page(p)
        .size(s)
        .totalItems(built.result().rows().size())
        .build();
  }

  public byte[] exportExcel(String shopId, String shopName, LocalDate from, LocalDate to, String q) {
    return documentService.generateMisExcel(document(shopId, shopName, from, to, q));
  }

  public byte[] exportPdf(String shopId, String shopName, LocalDate from, LocalDate to, String q) {
    return documentService.generateMisPdf(document(shopId, shopName, from, to, q));
  }

  /** The rows a period close freezes. Shares one code path with the report it must agree with. */
  Built buildForClose(String shopId, LocalDate from, LocalDate to) {
    return build(shopId, from, to, null);
  }

  private MisTabularDocumentRequest document(
      String shopId, String shopName, LocalDate from, LocalDate to, String q) {
    Built built = build(shopId, from, to, q);
    MisReportSupport.assertExportSize(built.result().rows().size());
    return MisTabularDocumentFactory.bankSummaryReport(
        "Bank Summary",
        shopName,
        LocalDateTime.now(),
        built.from(),
        built.to(),
        built.result().totals(),
        built.result().rows(),
        built.result().hasAdjustments());
  }

  private Built build(String shopId, LocalDate fromParam, LocalDate toParam, String q) {
    LocalDate from = MisDateRangeHelper.resolveFrom(fromParam);
    LocalDate to = MisDateRangeHelper.resolveTo(toParam);
    if (to.isBefore(from)) {
      throw new ValidationException("Period end cannot be before period start.");
    }

    Instant start = MisDateRangeHelper.startOfDay(from);
    Instant endExclusive = MisDateRangeHelper.startOfNextDay(to);

    // Only the close for the day immediately before the period can serve as its opening.
    // An older close would silently skip whatever moved in between, so it is not used.
    LocalDate priorEnd = from.minusDays(1);
    Optional<StockPeriodSnapshot> prior = misProductQueryService.findSnapshot(shopId, priorEnd);
    Map<String, BigDecimal> openingSnapshot = prior.map(StockPeriodSnapshot::getClosingByCompany).orElse(null);

    BankSummaryEngine.Movements movements =
        new BankSummaryEngine.Movements(
            misProductQueryService.findVendorInvoicesFrom(shopId, start),
            misProductQueryService.findCompletedSalesFrom(shopId, start),
            misProductQueryService.findRefundsFrom(shopId, start),
            misProductQueryService.findVendorReturnsFrom(shopId, start),
            misProductQueryService.findAllCorrections(shopId));

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            misProductQueryService.findAllInventory(shopId),
            movements,
            openingSnapshot,
            start,
            endExclusive);

    if (StringUtils.hasText(q)) {
      String needle = q.trim().toLowerCase(Locale.ROOT);
      List<MisBankSummaryRowDto> filtered =
          result.rows().stream()
              .filter(r -> r.getCompany() != null
                  && r.getCompany().toLowerCase(Locale.ROOT).contains(needle))
              .toList();
      // Totals follow the filter, so what is on screen always adds up to what is under it.
      result = new BankSummaryEngine.Result(
          filtered, BankSummaryEngine.totalsOf(filtered), result.hasAdjustments());
    }

    return new Built(
        from,
        to,
        prior.isPresent() ? SOURCE_SNAPSHOT : SOURCE_DERIVED,
        prior.map(StockPeriodSnapshot::getPeriodEnd).orElse(null),
        result);
  }

  record Built(
      LocalDate from,
      LocalDate to,
      String openingSource,
      LocalDate openingSnapshotDate,
      BankSummaryEngine.Result result) {}
}
