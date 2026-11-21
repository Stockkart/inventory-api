package com.inventory.pricing.domain.repository;

import com.inventory.pricing.domain.model.Pricing;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Implementation of {@link PricingRepositoryCustom}. Named {@code PricingRepositoryImpl} so Spring
 * Data wires it into {@link PricingRepository} as a repository fragment.
 */
@RequiredArgsConstructor
public class PricingRepositoryImpl implements PricingRepositoryCustom {

  private static final String RATE_NAME_FIELD = "rates.name";

  private final MongoTemplate mongoTemplate;

  @Override
  public List<String> findDistinctRateNamesByShopId(String shopId) {
    List<String> names =
        mongoTemplate
            .query(Pricing.class)
            .distinct(RATE_NAME_FIELD)
            .matching(Query.query(Criteria.where("shopId").is(shopId)))
            .as(String.class)
            .all();

    return names.stream()
        .filter(Objects::nonNull)
        .filter(name -> !name.isBlank())
        .distinct()
        .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
        .toList();
  }
}
