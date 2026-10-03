package com.inventory.product.labels;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.LabelLayoutDocument;
import com.inventory.product.domain.repository.LabelLayoutRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stand-in for {@link LabelLayoutRepository} used by the {@code LabelLayoutService}
 * property tests (tasks 3.8–3.11).
 *
 * <p>Backed by a map keyed by {@code shopId}, which also models the unique index on that field.
 * Only the methods the service touches are stubbed: {@code findByShopId}, {@code save}, {@code
 * findAll} and {@code count}. {@code save} assigns an id when the document has none and stores a
 * defensive copy so later mutation of the caller's instance cannot leak into "the database".
 */
final class InMemoryLabelLayoutRepository {

  private final Map<String, LabelLayoutDocument> byShopId = new LinkedHashMap<>();
  private final AtomicInteger saveCalls = new AtomicInteger();
  private final LabelLayoutRepository mock;

  InMemoryLabelLayoutRepository() {
    mock = mock(LabelLayoutRepository.class);
    when(mock.findByShopId(anyString()))
        .thenAnswer(inv -> Optional.ofNullable(byShopId.get(inv.getArgument(0))).map(this::copy));
    when(mock.save(any(LabelLayoutDocument.class)))
        .thenAnswer(
            inv -> {
              LabelLayoutDocument doc = inv.getArgument(0);
              saveCalls.incrementAndGet();
              if (doc.getId() == null) {
                doc.setId(UUID.randomUUID().toString());
              }
              byShopId.put(doc.getShopId(), copy(doc));
              return copy(doc);
            });
    when(mock.findAll()).thenAnswer(inv -> new ArrayList<>(byShopId.values()));
    when(mock.count()).thenAnswer(inv -> (long) byShopId.size());
  }

  /** The Mockito-backed repository to hand to the service under test. */
  LabelLayoutRepository repository() {
    return mock;
  }

  /** Snapshot of every stored document (one per shop). */
  List<LabelLayoutDocument> documents() {
    return byShopId.values().stream().map(this::copy).toList();
  }

  /** The stored document for a shop, if any. */
  Optional<LabelLayoutDocument> stored(String shopId) {
    return Optional.ofNullable(byShopId.get(shopId)).map(this::copy);
  }

  /** Number of documents currently stored. */
  int size() {
    return byShopId.size();
  }

  /** Number of {@code save} invocations seen so far. */
  int saveCalls() {
    return saveCalls.get();
  }

  private LabelLayoutDocument copy(LabelLayoutDocument d) {
    return new LabelLayoutDocument(
        d.getId(),
        d.getShopId(),
        d.getEnabledFieldKeys() == null ? null : List.copyOf(d.getEnabledFieldKeys()),
        d.getStickerSize(),
        d.getShowBarcodeText(),
        d.getShowFieldLabels(),
        d.getBlankValueBehavior(),
        d.getPrintMedia(),
        d.getSheetPreset(),
        d.getTemplate(),
        d.getBarcodePosition(),
        d.getCurrencyStyle(),
        d.getFieldZones() == null ? null : new LinkedHashMap<>(d.getFieldZones()),
        d.getFieldLabelOverrides() == null ? null : new LinkedHashMap<>(d.getFieldLabelOverrides()),
        d.getUpdatedAt(),
        d.getUpdatedByUserId());
  }
}
