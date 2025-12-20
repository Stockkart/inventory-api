package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Inventory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InventoryRepository extends MongoRepository<Inventory, String> {

  List<Inventory> findByShopId(String shopId);

  /**
   * Find all inventories by lotId.
   *
   * @param lotId the lot ID
   * @return list of inventories with the given lotId
   */
  List<Inventory> findByLotId(String lotId);

  /**
   * Find all inventories by shopId and lotId.
   *
   * @param shopId the shop ID
   * @param lotId the lot ID
   * @return list of inventories with the given shopId and lotId
   */
  List<Inventory> findByShopIdAndLotId(String shopId, String lotId);

  @Query("{ 'shopId': ?0, '$or': [ " +
      "{ 'barcode': { '$regex': ?1, '$options': 'i' } }, " +
      "{ 'name': { '$regex': ?1, '$options': 'i' } }, " +
      "{ 'companyName': { '$regex': ?1, '$options': 'i' } } " +
      "] }")
  List<Inventory> searchByShopIdAndQuery(String shopId, String query);

  List<Inventory> findByShopIdAndBarcode(String shopId, String barcode);
}

