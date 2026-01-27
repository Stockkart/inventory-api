package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.ParsedInventoryResult;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ParsedInventoryResultRepository extends MongoRepository<ParsedInventoryResult, String> {
  
  Optional<ParsedInventoryResult> findByUploadTokenId(String uploadTokenId);
}
