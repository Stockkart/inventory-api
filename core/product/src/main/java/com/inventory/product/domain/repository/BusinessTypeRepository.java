package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.BusinessType;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BusinessTypeRepository extends MongoRepository<BusinessType, String> {
}

