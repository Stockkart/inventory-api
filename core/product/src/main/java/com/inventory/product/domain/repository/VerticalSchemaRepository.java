package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.VerticalSchemaDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VerticalSchemaRepository extends MongoRepository<VerticalSchemaDocument, String> {

  Optional<VerticalSchemaDocument> findByVerticalIdAndVersion(String verticalId, String version);

  Optional<VerticalSchemaDocument> findByVerticalIdAndVersionAndStatus(
      String verticalId, String version, String status);

  boolean existsByVerticalIdAndStatus(String verticalId, String status);

  List<VerticalSchemaDocument> findByVerticalIdAndStatus(String verticalId, String status);

  List<VerticalSchemaDocument> findByStatus(String status);
}
