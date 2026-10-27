package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Product;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends MongoRepository<Product, String> {

  /**
   * Products by id, in one reply when there are up to 5,000. {@code findAllById} lets the driver
   * send its default first batch (101 documents) and fetch the rest with a second round trip
   * ({@code getMore}).
   */
  @Meta(cursorBatchSize = 5000)
  List<Product> findByIdIn(Collection<String> ids);

  Optional<Product> findByIdAndShopId(String id, String shopId);

  /** Exact barcode lookup within a shop; several products may share one barcode. */
  List<Product> findAllByShopIdAndBarcode(String shopId, String barcode);

  /** Candidates for identity matching / fork detection within a shop. */
  List<Product> findByShopIdAndNormalizedName(String shopId, String normalizedName);

  /** Every product in a shop sharing one HSN, for checking that they agree on a GST rate. */
  List<Product> findByShopIdAndHsn(String shopId, String hsn);

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
