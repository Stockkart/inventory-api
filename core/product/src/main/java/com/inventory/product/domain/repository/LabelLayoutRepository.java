package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.LabelLayoutDocument;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LabelLayoutRepository extends MongoRepository<LabelLayoutDocument, String> {

  Optional<LabelLayoutDocument> findByShopId(String shopId);
}
