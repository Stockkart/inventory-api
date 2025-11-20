package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Company;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CompanyRepository extends MongoRepository<Company, String> {
}

