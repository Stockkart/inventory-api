package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseRepository extends MongoRepository<Purchase, String> {

  Optional<Purchase> findByUserIdAndShopIdAndStatus(String userId, String shopId, PurchaseStatus status);

  Optional<Purchase> findByUserIdAndShopIdAndStatusIn(String userId, String shopId, List<PurchaseStatus> statuses);

  Page<Purchase> findByShopId(String shopId, Pageable pageable);

  Page<Purchase> findByShopIdAndUserId(String shopId, String userId, Pageable pageable);

  /**
   * Find purchases by shop ID and invoice number.
   *
   * @param shopId the shop ID
   * @param invoiceNo the invoice number
   * @return list of purchases matching the criteria
   */
  List<Purchase> findByShopIdAndInvoiceNo(String shopId, String invoiceNo);

  /**
   * Find purchases by shop ID and customer ID.
   *
   * @param shopId the shop ID
   * @param customerId the customer ID
   * @return list of purchases matching the criteria
   */
  List<Purchase> findByShopIdAndCustomerId(String shopId, String customerId);

  /**
   * Find purchases by shop ID, invoice number, and customer ID.
   *
   * @param shopId the shop ID
   * @param invoiceNo the invoice number
   * @param customerId the customer ID
   * @return list of purchases matching the criteria
   */
  List<Purchase> findByShopIdAndInvoiceNoAndCustomerId(String shopId, String invoiceNo, String customerId);

  /**
   * Find purchases by shop ID and list of customer IDs with pagination.
   *
   * @param shopId the shop ID
   * @param customerIds list of customer IDs
   * @param pageable pagination information
   * @return page of purchases matching the criteria
   */
  Page<Purchase> findByShopIdAndCustomerIdIn(String shopId, List<String> customerIds, Pageable pageable);

  /**
   * Find purchases by shop ID and invoice number using regex pattern (case-insensitive).
   *
   * @param shopId the shop ID
   * @param invoiceNoPattern the invoice number regex pattern
   * @return list of purchases matching the criteria
   */
  @Query("{ 'shopId': ?0, 'invoiceNo': { '$regex': ?1, '$options': 'i' } }")
  List<Purchase> findByShopIdAndInvoiceNoRegex(String shopId, String invoiceNoPattern);
}

