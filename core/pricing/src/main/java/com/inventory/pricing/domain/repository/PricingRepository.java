package com.inventory.pricing.domain.repository;

import com.inventory.pricing.domain.model.Pricing;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PricingRepository extends MongoRepository<Pricing, String> {

  List<Pricing> findByShopId(String shopId);
}
