package com.inventory.analytics.utils;

import com.inventory.analytics.rest.dto.response.InventoryAnalyticsDto;
import com.inventory.analytics.rest.dto.response.InventorySummaryDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * The Inventory tab's figures, gathered one lot at a time.
 *
 * <p>The summary is the same arithmetic {@link InventoryAnalyticsUtils#calculateSummary} does over
 * all lots (sums, counts, averages to 2 places). For each list only the rows up to the requested
 * page are kept (a bounded heap), plus the list's total; a row's place is the list's old order with
 * ties in lot order, which is what the old stable sort gave.
 */
public final class InventoryAnalyticsScan {

  /** The paged lists, named as in the response. */
  public enum ListKind {
    LOW_STOCK("lowStockItems", InventoryAnalyticsDto::getIsLowStock,
        Comparator.comparing(InventoryAnalyticsDto::getCurrentCount)),
    NOT_SELLING("notSellingItems",
        d -> (d.getSoldCount() == null || d.getSoldCount() == 0) && d.getCurrentCount() > 0,
        oldestReceivedFirst()),
    DEAD_STOCK("deadStockItems", InventoryAnalyticsDto::getIsDeadStock, oldestReceivedFirst()),
    ALL("allItems", d -> true, (a, b) -> 0);

    public final String field;
    final Predicate<InventoryAnalyticsDto> member;
    final Comparator<InventoryAnalyticsDto> order;

    ListKind(String field, Predicate<InventoryAnalyticsDto> member, Comparator<InventoryAnalyticsDto> order) {
      this.field = field;
      this.member = member;
      this.order = order;
    }

    /** By response field name ({@code lowStockItems}, …), or null. */
    public static ListKind byField(String field) {
      for (ListKind k : values()) {
        if (k.field.equals(field)) {
          return k;
        }
      }
      return null;
    }

    /** Most days since received first; no received date last. */
    private static Comparator<InventoryAnalyticsDto> oldestReceivedFirst() {
      return Comparator.comparing(InventoryAnalyticsDto::getDaysSinceReceived,
          Comparator.nullsLast(Comparator.reverseOrder()));
    }
  }

  private record Row(InventoryAnalyticsDto dto, long seq) {}

  private final Map<ListKind, Integer> keep = new EnumMap<>(ListKind.class);
  private final Map<ListKind, PriorityQueue<Row>> windows = new EnumMap<>(ListKind.class);
  private final Map<ListKind, Integer> totals = new EnumMap<>(ListKind.class);
  private long seq;

  private int lowStock;
  private int expired;
  private int expiringSoon;
  private int deadStock;
  private BigDecimal costValue = BigDecimal.ZERO;
  private BigDecimal retailValue = BigDecimal.ZERO;
  private BigDecimal potentialProfit = BigDecimal.ZERO;
  private BigDecimal turnover = BigDecimal.ZERO;
  private BigDecimal stockPercentage = BigDecimal.ZERO;

  /** Keep, for each given list, its first {@code rows} rows (lists not given only get a total). */
  public InventoryAnalyticsScan(Map<ListKind, Integer> rowsToKeep) {
    for (ListKind k : ListKind.values()) {
      totals.put(k, 0);
    }
    rowsToKeep.forEach((kind, rows) -> {
      keep.put(kind, rows);
      Comparator<Row> place = placeOf(kind);
      windows.put(kind, new PriorityQueue<>(place.reversed())); // head = the row that would drop out
    });
  }

  private static Comparator<Row> placeOf(ListKind kind) {
    return Comparator.<Row, InventoryAnalyticsDto>comparing(Row::dto, kind.order).thenComparingLong(Row::seq);
  }

  public void add(InventoryAnalyticsDto dto) {
    long n = seq++;
    if (Boolean.TRUE.equals(dto.getIsLowStock())) lowStock++;
    if (Boolean.TRUE.equals(dto.getIsExpired())) expired++;
    if (Boolean.TRUE.equals(dto.getIsExpiringSoon())) expiringSoon++;
    if (Boolean.TRUE.equals(dto.getIsDeadStock())) deadStock++;
    if (dto.getCostValue() != null) costValue = costValue.add(dto.getCostValue());
    if (dto.getRetailValue() != null) retailValue = retailValue.add(dto.getRetailValue());
    if (dto.getPotentialProfit() != null) potentialProfit = potentialProfit.add(dto.getPotentialProfit());
    if (dto.getTurnoverRatio() != null) turnover = turnover.add(dto.getTurnoverRatio());
    if (dto.getStockPercentage() != null) stockPercentage = stockPercentage.add(dto.getStockPercentage());

    for (ListKind kind : ListKind.values()) {
      if (!kind.member.test(dto)) {
        continue;
      }
      totals.merge(kind, 1, Integer::sum);
      PriorityQueue<Row> window = windows.get(kind);
      if (window == null) {
        continue;
      }
      int rows = keep.get(kind);
      if (rows <= 0) {
        continue;
      }
      Row row = new Row(dto, n);
      if (window.size() < rows) {
        window.add(row);
      } else if (placeOf(kind).compare(row, window.peek()) < 0) {
        window.poll();
        window.add(row);
      }
    }
  }

  /** Lots seen. */
  public int count() {
    return (int) seq;
  }

  public int total(ListKind kind) {
    return totals.get(kind);
  }

  /** Rows {@code [page*size, page*size+size)} of the list; needs {@code (page+1)*size} kept. */
  public List<InventoryAnalyticsDto> page(ListKind kind, int page, int size) {
    PriorityQueue<Row> window = windows.get(kind);
    if (window == null) {
      return List.of();
    }
    List<Row> rows = new ArrayList<>(window);
    rows.sort(placeOf(kind));
    int from = Math.min(rows.size(), page * size);
    int to = Math.min(rows.size(), from + size);
    List<InventoryAnalyticsDto> out = new ArrayList<>(to - from);
    for (Row r : rows.subList(from, to)) {
      out.add(r.dto());
    }
    return out;
  }

  /** Same figures as {@link InventoryAnalyticsUtils#calculateSummary} over every lot added. */
  public InventorySummaryDto summary() {
    int n = count();
    if (n == 0) {
      return new InventorySummaryDto(0, 0, 0, 0, 0,
          BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
    return new InventorySummaryDto(
        n, lowStock, expired, expiringSoon, deadStock, costValue, retailValue, potentialProfit,
        turnover.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP),
        stockPercentage.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP));
  }
}
