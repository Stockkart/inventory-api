package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Refund;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for Refund entities.
 */
@Repository
public interface RefundRepository extends MongoRepository<Refund, String> {

  /**
   * Find all refunds for a specific purchase.
   *
   * @param purchaseId the purchase ID
   * @return list of refunds for the purchase
   */
  List<Refund> findByPurchaseId(String purchaseId);

  /**
   * Find all refunds for a specific shop.
   *
   * @param shopId the shop ID
   * @param pageable pagination information
   * @return page of refunds for the shop
   */
  Page<Refund> findByShopId(String shopId, Pageable pageable);

  /**
   * Find all refunds for a specific purchase, ordered by creation date descending.
   *
   * @param purchaseId the purchase ID
   * @return list of refunds for the purchase, ordered by creation date descending
   */
  List<Refund> findByPurchaseIdOrderByCreatedAtDesc(String purchaseId);

  /**
   * Find all refunds for a list of purchase IDs.
   *
   * @param purchaseIds list of purchase IDs
   * @param pageable pagination information
   * @return page of refunds for the purchases
   */
  Page<Refund> findByPurchaseIdIn(List<String> purchaseIds, Pageable pageable);

  /**
   * Find refunds by shop ID and created-at date range (inclusive).
   * Used for GSTR-1 CDNR/CDNUR and other period-based reports.
   */
  List<Refund> findByShopIdAndCreatedAtBetween(String shopId, Instant start, Instant end);
}

