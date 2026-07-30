package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.BarcodePool;
import com.inventory.product.domain.model.enums.BarcodePoolStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BarcodePoolRepository extends MongoRepository<BarcodePool, String> {

  Optional<BarcodePool> findByShopIdAndCode(String shopId, String code);

  List<BarcodePool> findByShopIdAndCodeIn(String shopId, Collection<String> codes);

  List<BarcodePool> findByShopIdAndStatus(String shopId, BarcodePoolStatus status, Pageable pageable);

  List<BarcodePool> findByShopId(String shopId, Pageable pageable);

  Optional<BarcodePool> findByShopIdAndProductId(String shopId, String productId);

  @Query("{ 'shopId': ?0, '$or': [ " +
      "{ 'code': { '$regex': ?1, '$options': 'i' } }, " +
      "{ 'labelName': { '$regex': ?1, '$options': 'i' } }, " +
      "{ 'labelCompany': { '$regex': ?1, '$options': 'i' } } " +
      "] }")
  List<BarcodePool> searchByShopIdAndQuery(String shopId, String query, Pageable pageable);
}
