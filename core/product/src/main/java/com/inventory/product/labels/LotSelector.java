package com.inventory.product.labels;

import com.inventory.product.domain.model.Inventory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Picks the lot whose values a barcode label should print when a product has several lots (Req
 * 6.3).
 *
 * <p>Rule:
 *
 * <ol>
 *   <li>Candidates = lots with {@code currentBaseCount != null && currentBaseCount > 0}. If that set
 *       is empty, candidates = all lots.
 *   <li>Pick the maximum by {@code receivedDate} (nulls last), then by {@code createdAt} (nulls
 *       last), then by {@code id} (nulls last, natural order) so the choice is deterministic.
 *   <li>Empty or {@code null} input → {@link Optional#empty()}.
 * </ol>
 *
 * <p>The legacy {@code price} field in {@code BarcodeService.labels} keeps its own lot rule; this
 * class is only used for the configurable label fields.
 */
public final class LotSelector {

  /** "Latest first" ordering; a {@code null} key sorts after every non-null key. */
  private static final Comparator<Inventory> PREFERENCE =
      Comparator.comparing(
              Inventory::getReceivedDate, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
          .thenComparing(
              Inventory::getCreatedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
          .thenComparing(
              Inventory::getId, Comparator.nullsFirst(Comparator.<String>naturalOrder()));

  private LotSelector() {}

  /**
   * Selects the preferred lot from {@code lots}.
   *
   * @param lots lots of a single product; may be {@code null}, empty, or contain {@code null}
   *     elements (which are ignored)
   * @return the preferred lot, or empty when there is none
   */
  public static Optional<Inventory> select(List<Inventory> lots) {
    if (lots == null || lots.isEmpty()) {
      return Optional.empty();
    }
    List<Inventory> nonNull = lots.stream().filter(Objects::nonNull).toList();
    if (nonNull.isEmpty()) {
      return Optional.empty();
    }
    List<Inventory> inStock = nonNull.stream().filter(LotSelector::isInStock).toList();
    List<Inventory> candidates = inStock.isEmpty() ? nonNull : inStock;
    // Max under PREFERENCE: nullsFirst makes nulls the smallest, so they lose the max.
    return candidates.stream().max(PREFERENCE);
  }

  /**
   * Groups {@code lots} by {@code productId} and applies {@link #select(List)} to each group.
   *
   * <p>Lots with a {@code null} {@code productId} are skipped. Iteration order of the result follows
   * the first appearance of each product in {@code lots}.
   *
   * @param lots lots of any number of products; may be {@code null} or empty
   * @return one selected lot per product id that had at least one lot
   */
  public static Map<String, Inventory> selectPerProduct(List<Inventory> lots) {
    Map<String, Inventory> selected = new LinkedHashMap<>();
    if (lots == null || lots.isEmpty()) {
      return selected;
    }
    Map<String, List<Inventory>> byProduct = new LinkedHashMap<>();
    for (Inventory lot : lots) {
      if (lot == null || lot.getProductId() == null) {
        continue;
      }
      byProduct.computeIfAbsent(lot.getProductId(), k -> new ArrayList<>()).add(lot);
    }
    byProduct.forEach((productId, group) -> select(group).ifPresent(l -> selected.put(productId, l)));
    return selected;
  }

  private static boolean isInStock(Inventory lot) {
    Integer count = lot.getCurrentBaseCount();
    return count != null && count > 0;
  }
}
