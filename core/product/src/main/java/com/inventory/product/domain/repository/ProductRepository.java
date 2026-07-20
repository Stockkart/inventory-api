package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Product;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends MongoRepository<Product, String> {

  Optional<Product> findByIdAndShopId(String id, String shopId);

  /** Candidates for identity matching / fork detection within a shop. */
  List<Product> findByShopIdAndNormalizedName(String shopId, String normalizedName);

  /** Typeahead for registration: case-insensitive match on name, company, or barcode. */
  @Query("{ 'shopId': ?0, '$or': [ " +
      "{ 'name': { '$regex': ?1, '$options': 'i' } }, " +
      "{ 'companyName': { '$regex': ?1, '$options': 'i' } }, " +
      "{ 'barcode': { '$regex': ?1, '$options': 'i' } } " +
      "] }")
  List<Product> suggestByShopIdAndQuery(String shopId, String query, Pageable pageable);

  /**
   * Unbounded text match used to drive inventory search now that identity lives on Product.
   * Returns only the {@code _id} of each matching product.
   */
  @Query(value = "{ 'shopId': ?0, '$or': [ " +
      "{ 'name': { '$regex': ?1, '$options': 'i' } }, " +
      "{ 'companyName': { '$regex': ?1, '$options': 'i' } }, " +
      "{ 'barcode': { '$regex': ?1, '$options': 'i' } } " +
      "] }", fields = "{ '_id': 1 }")
  List<Product> findMatchingIdsByShopIdAndQuery(String shopId, String query);
}
