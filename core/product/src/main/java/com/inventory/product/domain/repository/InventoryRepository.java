package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Inventory;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface InventoryRepository extends MongoRepository<Inventory, String> {

  List<Inventory> findByIdIn(List<String> ids);

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

  /**
   * Check if a lotId exists for a given shop.
   *
   * @param shopId the shop ID
   * @param lotId the lot ID
   * @return true if lotId exists for the shop
   */
  boolean existsByShopIdAndLotId(String shopId, String lotId);

  /**
   * Find distinct lotIds for a shop with aggregation.
   * Returns lot summaries with product count and timestamps.
   */
  @Aggregation(pipeline = {
      "{ $match: { 'shopId': ?0, 'lotId': { $exists: true, $ne: null } } }",
      "{ $group: { " +
          "_id: '$lotId', " +
          "productCount: { $sum: 1 }, " +
          "createdAt: { $min: '$createdAt' }, " +
          "lastUpdated: { $max: '$updatedAt' }, " +
          "firstProductName: { $first: '$name' } " +
      "} }",
      "{ $sort: { lastUpdated: -1 } }",
      "{ $project: { " +
          "lotId: '$_id', " +
          "productCount: 1, " +
          "createdAt: 1, " +
          "lastUpdated: 1, " +
          "firstProductName: 1, " +
          "_id: 0 " +
      "} }"
  })
  List<LotSummaryProjection> getLotSummariesByShopId(String shopId);

  /**
   * Find distinct lotIds for a shop matching search query.
   */
  @Aggregation(pipeline = {
      "{ $match: { " +
          "'shopId': ?0, " +
          "'lotId': { $exists: true, $ne: null, $regex: ?1, $options: 'i' } " +
      "} }",
      "{ $group: { " +
          "_id: '$lotId', " +
          "productCount: { $sum: 1 }, " +
          "createdAt: { $min: '$createdAt' }, " +
          "lastUpdated: { $max: '$updatedAt' }, " +
          "firstProductName: { $first: '$name' } " +
      "} }",
      "{ $sort: { lastUpdated: -1 } }",
      "{ $project: { " +
          "lotId: '$_id', " +
          "productCount: 1, " +
          "createdAt: 1, " +
          "lastUpdated: 1, " +
          "firstProductName: 1, " +
          "_id: 0 " +
      "} }"
  })
  List<LotSummaryProjection> searchLotSummariesByShopId(String shopId, String query);

  /**
   * Projection class for lot summary aggregation results.
   * Using a concrete class instead of interface to avoid Spring Data MongoDB projection issues.
   */
  public static class LotSummaryProjection {
    private String lotId;
    private Integer productCount;
    private Instant createdAt;
    private Instant lastUpdated;
    private String firstProductName;

    public LotSummaryProjection() {
    }

    public String getLotId() {
      return lotId;
    }

    public void setLotId(String lotId) {
      this.lotId = lotId;
    }

    public Integer getProductCount() {
      return productCount;
    }

    public void setProductCount(Integer productCount) {
      this.productCount = productCount;
    }

    public Instant getCreatedAt() {
      return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
      this.createdAt = createdAt;
    }

    public Instant getLastUpdated() {
      return lastUpdated;
    }

    public void setLastUpdated(Instant lastUpdated) {
      this.lastUpdated = lastUpdated;
    }

    public String getFirstProductName() {
      return firstProductName;
    }

    public void setFirstProductName(String firstProductName) {
      this.firstProductName = firstProductName;
    }
  }
}

