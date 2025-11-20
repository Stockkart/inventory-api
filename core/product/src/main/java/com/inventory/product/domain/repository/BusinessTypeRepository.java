package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.BusinessType;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BusinessTypeRepository extends MongoRepository<BusinessType, String> {
}

