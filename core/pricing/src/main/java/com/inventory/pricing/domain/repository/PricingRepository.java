package com.inventory.pricing.domain.repository;

import com.inventory.pricing.domain.model.Pricing;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PricingRepository extends MongoRepository<Pricing, String> {

  boolean existsById(String id);
}
