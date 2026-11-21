package com.inventory.product.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.ShopRateRulesDocument;
import com.inventory.product.domain.repository.ShopRateRulesRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory stand-in for {@link ShopRateRulesRepository}; keyed by shopId like the unique index. */
public final class InMemoryShopRateRulesRepository {

  private final Map<String, ShopRateRulesDocument> store = new LinkedHashMap<>();
  private final ShopRateRulesRepository mock;

  public InMemoryShopRateRulesRepository() {
    mock = mock(ShopRateRulesRepository.class);
    when(mock.findByShopId(anyString()))
        .thenAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
    when(mock.save(any(ShopRateRulesDocument.class)))
        .thenAnswer(
            inv -> {
              ShopRateRulesDocument doc = inv.getArgument(0);
              if (doc.getId() == null) {
                doc.setId(UUID.randomUUID().toString());
              }
              store.put(doc.getShopId(), doc);
              return doc;
            });
  }

  public ShopRateRulesRepository asRepository() {
    return mock;
  }

  public Optional<ShopRateRulesDocument> stored(String shopId) {
    return Optional.ofNullable(store.get(shopId));
  }
}
