package com.inventory.pricing.domain.repository;

import com.inventory.pricing.domain.model.Pricing;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PricingRepository
    extends MongoRepository<Pricing, String>, PricingRepositoryCustom {

  List<Pricing> findByShopId(String shopId);

  /**
   * Pricing records by id, in one reply when there are up to 5,000. {@code findAllById} lets the
   * driver send its default first batch (101 documents) and fetch the rest with a second round
   * trip ({@code getMore}).
   */
  @Meta(cursorBatchSize = 5000)
  List<Pricing> findByIdIn(Collection<String> ids);
}
