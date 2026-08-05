package com.inventory.analytics.mis.support;

import com.inventory.analytics.mis.domain.MisMoneyFilter;
import com.inventory.common.exception.ValidationException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class MisReportSupport {

  public static final int MAX_EXPORT_ROWS = 10_000;
  public static final int DEFAULT_PAGE_SIZE = 50;
  public static final int MAX_PAGE_SIZE = 200;

  private MisReportSupport() {}

  public static int safePage(Integer page) {
    return page == null || page < 0 ? 0 : page;
  }

  public static int safeSize(Integer size) {
    if (size == null || size < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  public static <T> List<T> paginate(List<T> all, int page, int size) {
    if (all == null || all.isEmpty()) {
      return List.of();
    }
    int from = Math.min(page * size, all.size());
    int to = Math.min(from + size, all.size());
    if (from >= to) {
      return List.of();
    }
    return new ArrayList<>(all.subList(from, to));
  }

  public static void assertExportSize(int rowCount) {
    if (rowCount > MAX_EXPORT_ROWS) {
      throw new ValidationException(
          "Too many rows for export (" + rowCount + "). Narrow filters (max " + MAX_EXPORT_ROWS + ").");
    }
  }

  public static boolean matchesMoneyFilter(
      MisMoneyFilter filter, BigDecimal cash, BigDecimal online, BigDecimal credit) {
    BigDecimal c = MisMoneyTenderHelper.nz(cash);
    BigDecimal o = MisMoneyTenderHelper.nz(online);
    BigDecimal cr = MisMoneyTenderHelper.nz(credit);
    int positiveLegs = 0;
    if (c.signum() > 0) positiveLegs++;
    if (o.signum() > 0) positiveLegs++;
    if (cr.signum() > 0) positiveLegs++;
    return switch (filter == null ? MisMoneyFilter.ALL : filter) {
      case ALL -> true;
      case HAS_CASH -> c.signum() > 0;
      case HAS_ONLINE -> o.signum() > 0;
      case HAS_CREDIT -> cr.signum() > 0;
      case FULLY_PAID -> cr.signum() == 0 && (c.signum() > 0 || o.signum() > 0);
      case MIXED -> positiveLegs > 1;
    };
  }

  public static String money(BigDecimal v) {
    return MisMoneyTenderHelper.nz(v).toPlainString();
  }

  /** Prefer persisted UUID txnId; fall back to Mongo id until backfill completes. */
  public static String resolveTxnId(String txnId, String mongoId) {
    if (txnId != null && !txnId.isBlank()) {
      return txnId.trim();
    }
    return mongoId;
  }
}
