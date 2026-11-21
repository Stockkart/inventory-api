// Feature: barcode-label-layout, Property 12: Lot selection prefers in-stock, latest received, latest created
package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.product.domain.model.Inventory;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 12: Lot selection prefers in-stock, latest received, latest created.
 *
 * <p><b>Validates: Requirements 6.3</b>
 */
class LotSelectorProperties {

  /** Small pool so that ties on receivedDate / createdAt happen often. */
  private static final List<Instant> INSTANT_POOL =
      List.of(
          Instant.parse("2024-01-01T00:00:00Z"),
          Instant.parse("2024-06-15T12:00:00Z"),
          Instant.parse("2025-03-05T08:30:00Z"));

  // Feature: barcode-label-layout, Property 12: Lot selection prefers in-stock, latest received, latest created
  @Property(tries = 100)
  void emptyListSelectsNothing() {
    assertEquals(Optional.empty(), LotSelector.select(List.of()));
    assertEquals(Optional.empty(), LotSelector.select(null));
  }

  // Feature: barcode-label-layout, Property 12: Lot selection prefers in-stock, latest received, latest created
  @Property(tries = 100)
  void selectedLotIsInStockLatestReceivedLatestCreatedMaxId(@ForAll("lotLists") List<Inventory> lots) {
    Optional<Inventory> result = LotSelector.select(lots);

    if (lots.isEmpty()) {
      assertEquals(Optional.empty(), result);
      return;
    }

    assertTrue(result.isPresent(), "non-empty input must select a lot");
    Inventory chosen = result.get();
    assertTrue(lots.contains(chosen), "chosen lot must come from the input");

    List<Inventory> inStock = lots.stream().filter(LotSelectorProperties::isInStock).toList();
    List<Inventory> candidates = inStock.isEmpty() ? lots : inStock;
    if (!inStock.isEmpty()) {
      assertTrue(isInStock(chosen), "chosen lot must be in stock when any lot is in stock");
    }

    // Maximum receivedDate among candidates, nulls lose to any non-null.
    Optional<Instant> maxReceived =
        candidates.stream().map(Inventory::getReceivedDate).filter(Objects::nonNull).max(Comparator.naturalOrder());
    assertEquals(maxReceived.orElse(null), chosen.getReceivedDate(), "receivedDate must be the maximum");

    // Among equal receivedDate, maximum createdAt, nulls lose.
    List<Inventory> sameReceived =
        candidates.stream()
            .filter(l -> Objects.equals(l.getReceivedDate(), chosen.getReceivedDate()))
            .toList();
    Optional<Instant> maxCreated =
        sameReceived.stream().map(Inventory::getCreatedAt).filter(Objects::nonNull).max(Comparator.naturalOrder());
    assertEquals(maxCreated.orElse(null), chosen.getCreatedAt(), "createdAt must be the maximum");

    // Among those equal, maximum id.
    String maxId =
        sameReceived.stream()
            .filter(l -> Objects.equals(l.getCreatedAt(), chosen.getCreatedAt()))
            .map(Inventory::getId)
            .max(Comparator.naturalOrder())
            .orElseThrow();
    assertEquals(maxId, chosen.getId(), "id must be the maximum among full ties");
  }

  // Feature: barcode-label-layout, Property 12: Lot selection prefers in-stock, latest received, latest created
  @Property(tries = 100)
  void selectPerProductMatchesSelectOnEachGroup(@ForAll("lotLists") List<Inventory> lots) {
    Map<String, Inventory> perProduct = LotSelector.selectPerProduct(lots);

    Set<String> expectedKeys =
        lots.stream().map(Inventory::getProductId).filter(Objects::nonNull).collect(Collectors.toSet());
    assertEquals(expectedKeys, new HashSet<>(perProduct.keySet()));

    for (String productId : expectedKeys) {
      List<Inventory> group = lots.stream().filter(l -> productId.equals(l.getProductId())).toList();
      Inventory expected = LotSelector.select(group).orElseThrow();
      assertSame(expected, perProduct.get(productId), "per-product selection must equal select(group)");
    }
  }

  @Provide
  Arbitrary<List<Inventory>> lotLists() {
    Arbitrary<Integer> counts =
        Arbitraries.oneOf(
            Arbitraries.just((Integer) null),
            Arbitraries.just(0),
            Arbitraries.integers().between(-50, -1),
            Arbitraries.integers().between(1, 500));
    Arbitrary<Instant> instants = Arbitraries.of(INSTANT_POOL).injectNull(0.3);
    Arbitrary<String> productIds = Arbitraries.of("p1", "p2", "p3").injectNull(0.15);

    Arbitrary<Inventory> lotWithoutId =
        Combinators.combine(counts, instants, instants, productIds)
            .as(
                (count, received, created, productId) -> {
                  Inventory lot = new Inventory();
                  lot.setCurrentBaseCount(count);
                  lot.setReceivedDate(received);
                  lot.setCreatedAt(created);
                  lot.setProductId(productId);
                  return lot;
                });

    return lotWithoutId
        .list()
        .ofMinSize(0)
        .ofMaxSize(12)
        .map(
            list -> {
              // Distinct ids so the final tie-break is total.
              for (int i = 0; i < list.size(); i++) {
                list.get(i).setId(String.format("lot-%02d", i));
              }
              return list;
            });
  }

  private static boolean isInStock(Inventory lot) {
    Integer count = lot.getCurrentBaseCount();
    return count != null && count > 0;
  }
}
