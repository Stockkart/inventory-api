package com.inventory.analytics.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.analytics.rest.dto.response.InventoryAnalyticsDto;
import com.inventory.analytics.utils.InventoryAnalyticsScan.ListKind;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * The batched Inventory-tab figures against the old all-at-once code: summary from {@link
 * InventoryAnalyticsUtils#calculateSummary}, lists filtered and sorted exactly as the service did,
 * then compared page by page. Random lots with many ties (same stock, same days), zero received,
 * and missing values.
 */
class InventoryAnalyticsScanTest {

  private static List<InventoryAnalyticsDto> randomLots(long seed, int n, boolean nullDays) {
    Random r = new Random(seed);
    List<InventoryAnalyticsDto> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      InventoryAnalyticsDto d = new InventoryAnalyticsDto();
      d.setInventoryId("lot-" + i);
      int received = r.nextInt(5) == 0 ? 0 : r.nextInt(60);
      int sold = received == 0 ? 0 : (r.nextBoolean() ? 0 : r.nextInt(received + 1));
      d.setReceivedCount(received);
      d.setSoldCount(sold);
      d.setCurrentCount(received - sold);
      d.setStockPercentage(BigDecimal.valueOf(r.nextInt(10001), 2));
      d.setIsLowStock(r.nextInt(4) == 0);
      d.setIsDeadStock(r.nextInt(5) == 0);
      d.setIsExpired(r.nextInt(9) == 0);
      d.setIsExpiringSoon(r.nextInt(7) == 0);
      d.setDaysSinceReceived(nullDays && r.nextInt(25) == 0 ? null : (long) r.nextInt(40));
      d.setTurnoverRatio(BigDecimal.valueOf(r.nextInt(100000), 4));
      d.setCostValue(r.nextInt(30) == 0 ? null : BigDecimal.valueOf(r.nextInt(1_000_000), 2));
      d.setRetailValue(BigDecimal.valueOf(r.nextInt(2_000_000), 2));
      d.setPotentialProfit(BigDecimal.valueOf(r.nextInt(500_000) - 100_000, 2));
      out.add(d);
    }
    return out;
  }

  /** The service's old lists: the same filters and sorts over every lot. */
  private static List<InventoryAnalyticsDto> oldList(List<InventoryAnalyticsDto> all, ListKind kind) {
    Predicate<InventoryAnalyticsDto> keep = switch (kind) {
      case LOW_STOCK -> InventoryAnalyticsDto::getIsLowStock;
      case NOT_SELLING -> dto -> (dto.getSoldCount() == null || dto.getSoldCount() == 0) && dto.getCurrentCount() > 0;
      case DEAD_STOCK -> InventoryAnalyticsDto::getIsDeadStock;
      case ALL -> dto -> true;
    };
    List<InventoryAnalyticsDto> out = all.stream().filter(keep).collect(Collectors.toList());
    switch (kind) {
      case LOW_STOCK -> out.sort((a, b) -> a.getCurrentCount().compareTo(b.getCurrentCount()));
      case NOT_SELLING, DEAD_STOCK -> out.sort((a, b) -> {
        if (a.getDaysSinceReceived() == null) return 1;
        if (b.getDaysSinceReceived() == null) return -1;
        return b.getDaysSinceReceived().compareTo(a.getDaysSinceReceived());
      });
      case ALL -> { }
    }
    return out;
  }

  private static InventoryAnalyticsScan scan(List<InventoryAnalyticsDto> all, int keepRows) {
    Map<ListKind, Integer> keep = new EnumMap<>(ListKind.class);
    for (ListKind k : ListKind.values()) {
      keep.put(k, keepRows);
    }
    InventoryAnalyticsScan scan = new InventoryAnalyticsScan(keep);
    all.forEach(scan::add);
    return scan;
  }

  @RepeatedTest(20)
  void everyPageAndTheSummaryMatchTheOldCode(org.junit.jupiter.api.RepetitionInfo rep) {
    List<InventoryAnalyticsDto> all = randomLots(rep.getCurrentRepetition(), 900, false);
    InventoryAnalyticsScan scan = scan(all, all.size());

    assertThat(scan.summary()).usingRecursiveComparison().isEqualTo(new InventoryAnalyticsUtils().calculateSummary(all));
    assertThat(scan.count()).isEqualTo(all.size());
    for (ListKind kind : ListKind.values()) {
      List<InventoryAnalyticsDto> old = oldList(all, kind);
      assertThat(scan.total(kind)).as(kind.name()).isEqualTo(old.size());
      for (int size : new int[] {7, 50}) {
        List<InventoryAnalyticsDto> paged = new ArrayList<>();
        for (int page = 0; page * size < old.size() + size; page++) {
          paged.addAll(scan.page(kind, page, size));
        }
        assertThat(paged).as(kind + " size " + size).containsExactlyElementsOf(old);
      }
    }
  }

  @Test
  void aWindowOfOnePageHoldsTheSameFirstPage() {
    List<InventoryAnalyticsDto> all = randomLots(42, 3_000, false);
    InventoryAnalyticsScan small = scan(all, 50);
    for (ListKind kind : ListKind.values()) {
      List<InventoryAnalyticsDto> old = oldList(all, kind);
      assertThat(small.page(kind, 0, 50)).as(kind.name()).containsExactlyElementsOf(old.subList(0, Math.min(50, old.size())));
      assertThat(small.total(kind)).isEqualTo(old.size());
    }
  }

  @Test
  void lotsWithNoReceivedDateGoLastInLotOrder() {
    List<InventoryAnalyticsDto> all = randomLots(7, 900, true);
    InventoryAnalyticsScan scan = scan(all, all.size());
    for (ListKind kind : List.of(ListKind.NOT_SELLING, ListKind.DEAD_STOCK)) {
      List<InventoryAnalyticsDto> got = scan.page(kind, 0, all.size());
      List<InventoryAnalyticsDto> dated = got.stream().filter(d -> d.getDaysSinceReceived() != null).toList();
      List<InventoryAnalyticsDto> undated = got.stream().filter(d -> d.getDaysSinceReceived() == null).toList();
      assertThat(got).containsExactlyElementsOf(concat(dated, undated));
      assertThat(dated).isSortedAccordingTo((a, b) -> b.getDaysSinceReceived().compareTo(a.getDaysSinceReceived()));
      List<InventoryAnalyticsDto> undatedInLotOrder = oldList(all, kind).stream()
          .filter(d -> d.getDaysSinceReceived() == null).sorted((a, b) -> Integer.compare(all.indexOf(a), all.indexOf(b))).toList();
      assertThat(undated).containsExactlyElementsOf(undatedInLotOrder);
    }
  }

  private static <T> List<T> concat(List<T> a, List<T> b) {
    List<T> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  @Test
  void noLotsGivesTheOldEmptySummary() {
    InventoryAnalyticsScan scan = scan(List.of(), 50);
    assertThat(scan.summary()).usingRecursiveComparison().isEqualTo(new InventoryAnalyticsUtils().calculateSummary(List.of()));
    assertThat(scan.page(ListKind.ALL, 0, 50)).isEmpty();
  }
}
