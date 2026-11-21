package com.inventory.product.cardlayout;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.CardLayoutDocument;
import com.inventory.product.domain.repository.CardLayoutRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stand-in for {@link CardLayoutRepository} used by the service property tests.
 *
 * <p>Keyed by {@code shopId + "/" + surfaceId}, which also models the unique compound index. Stores
 * defensive copies so caller-side mutation cannot leak into "the database".
 */
final class InMemoryCardLayoutRepository {

  private final Map<String, CardLayoutDocument> store = new LinkedHashMap<>();
  private final AtomicInteger saveCalls = new AtomicInteger();
  private final CardLayoutRepository mock;

  InMemoryCardLayoutRepository() {
    mock = mock(CardLayoutRepository.class);
    when(mock.findByShopId(anyString()))
        .thenAnswer(
            inv -> {
              String shopId = inv.getArgument(0);
              List<CardLayoutDocument> out = new ArrayList<>();
              store.values().stream().filter(d -> shopId.equals(d.getShopId())).map(this::copy).forEach(out::add);
              return out;
            });
    when(mock.findByShopIdAndSurfaceId(anyString(), anyString()))
        .thenAnswer(
            inv -> Optional.ofNullable(store.get(key(inv.getArgument(0), inv.getArgument(1)))).map(this::copy));
    when(mock.save(any(CardLayoutDocument.class)))
        .thenAnswer(
            inv -> {
              CardLayoutDocument doc = inv.getArgument(0);
              saveCalls.incrementAndGet();
              if (doc.getId() == null) {
                doc.setId(UUID.randomUUID().toString());
              }
              store.put(key(doc.getShopId(), doc.getSurfaceId()), copy(doc));
              return copy(doc);
            });
    when(mock.count()).thenAnswer(inv -> (long) store.size());
  }

  CardLayoutRepository asRepository() {
    return mock;
  }

  int saveCalls() {
    return saveCalls.get();
  }

  int size() {
    return store.size();
  }

  Optional<CardLayoutDocument> stored(String shopId, String surfaceId) {
    return Optional.ofNullable(store.get(key(shopId, surfaceId))).map(this::copy);
  }

  /** Snapshot of the whole store for before/after comparisons. */
  Map<String, CardLayoutDocument> snapshot() {
    Map<String, CardLayoutDocument> snap = new LinkedHashMap<>();
    store.forEach((k, v) -> snap.put(k, copy(v)));
    return snap;
  }

  private static String key(String shopId, String surfaceId) {
    return shopId + "/" + surfaceId;
  }

  private CardLayoutDocument copy(CardLayoutDocument d) {
    // Round-trip through the domain converter gives a deep copy of the nested shape.
    return new CardLayoutDocument(
        d.getId(),
        d.getShopId(),
        d.getSurfaceId(),
        d.getVariants() == null
            ? null
            : CardLayoutDocuments.fromLayouts(CardLayoutDocuments.toLayouts(d)),
        d.getUpdatedAt(),
        d.getUpdatedByUserId());
  }
}
