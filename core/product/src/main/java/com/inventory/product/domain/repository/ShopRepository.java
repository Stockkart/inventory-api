package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Shop;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ShopRepository extends MongoRepository<Shop, String> {
}

