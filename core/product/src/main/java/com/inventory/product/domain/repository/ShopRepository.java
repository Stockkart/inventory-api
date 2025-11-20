package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Shop;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShopRepository extends MongoRepository<Shop, String> {
}

