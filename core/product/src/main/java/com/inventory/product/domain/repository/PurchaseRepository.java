package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.enums.DocumentType;
import com.inventory.product.domain.model.enums.EstimateState;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseRepository extends MongoRepository<Purchase, String> {

  Optional<Purchase> findByUserIdAndShopIdAndStatus(String userId, String shopId, PurchaseStatus status);

  List<Purchase> findByUserIdAndShopIdAndStatusOrderByUpdatedAtDesc(
      String userId, String shopId, PurchaseStatus status);

  List<Purchase> findByShopIdAndStatus(String shopId, PurchaseStatus status);

  List<Purchase> findByShopIdAndDocumentTypeOrderByUpdatedAtDesc(
      String shopId, DocumentType documentType);

  List<Purchase> findByShopIdAndDocumentTypeAndEstimateStateOrderByUpdatedAtDesc(
      String shopId, DocumentType documentType, EstimateState estimateState);

  Page<Purchase> findByShopIdAndDocumentTypeAndEstimateStateNot(
      String shopId, DocumentType documentType, EstimateState excludedState, Pageable pageable);

  Page<Purchase> findByShopIdAndDocumentTypeAndEstimateState(
      String shopId, DocumentType documentType, EstimateState estimateState, Pageable pageable);

  Optional<Purchase> findByIdAndUserIdAndShopId(String id, String userId, String shopId);

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

  /**
   * Find purchases by shop ID and sold-at date range (inclusive).
   * Used for GSTR-1 and other period-based reports.
   */
  List<Purchase> findByShopIdAndSoldAtBetween(String shopId, Instant start, Instant end);

  /**
   * Find completed purchases by shop ID and sold-at date range (inclusive).
   * Used for GSTR-1 so only invoiced sales are included.
   */
  List<Purchase> findByShopIdAndStatusAndSoldAtBetween(String shopId, PurchaseStatus status, Instant start, Instant end);

  /** Completed sales on or after an instant, for rolling live stock counters backwards. */
  @Query("{ 'shopId': ?0, 'status': ?1, 'soldAt': { '$gte': ?2 } }")
  List<Purchase> findByShopIdAndStatusAndSoldAtFrom(
      String shopId, PurchaseStatus status, Instant startInclusive);

  /**
   * Find completed purchases in a period: soldAt in range, or updatedAt in range (e.g. completed in period).
   * Ensures purchases completed in the period are included even if soldAt was never set or is from cart-creation.
   */
  @Query("{ 'shopId': ?0, 'status': ?1, '$or': [ "
      + "{ 'soldAt': { '$gte': ?2, '$lte': ?3 } }, "
      + "{ 'updatedAt': { '$gte': ?2, '$lte': ?3 } } "
      + "] }")
  List<Purchase> findCompletedPurchasesInPeriod(String shopId, PurchaseStatus status, Instant rangeStart, Instant rangeEnd);

  /**
   * True if the shop has at least one completed REGULAR (or legacy null-mode) sale with an invoice
   * number.
   */
  @Query(
      value =
          "{ 'shopId': ?0, 'status': 'COMPLETED', 'invoiceNo': { '$exists': true, '$nin': [null, ''] },"
              + " '$or': [ { 'billingMode': 'REGULAR' }, { 'billingMode': null }, { 'billingMode': { '$exists': false } } ] }",
      exists = true)
  boolean existsCompletedRegularInvoice(String shopId);

  /** Invoice numbers for completed REGULAR (or legacy) sales — used for sequence backfill. */
  @Query(
      value =
          "{ 'shopId': ?0, 'status': 'COMPLETED', 'invoiceNo': { '$exists': true, '$nin': [null, ''] },"
              + " '$or': [ { 'billingMode': 'REGULAR' }, { 'billingMode': null }, { 'billingMode': { '$exists': false } } ] }",
      fields = "{ 'invoiceNo': 1 }")
  List<Purchase> findCompletedRegularPurchasesForInvoiceNos(String shopId);

  default List<String> findRegularInvoiceNosByShopId(String shopId) {
    return findCompletedRegularPurchasesForInvoiceNos(shopId).stream()
        .map(Purchase::getInvoiceNo)
        .filter(no -> no != null && !no.isBlank())
        .toList();
  }

  /**
   * A page of the shop's sales narrowed by any combination of invoice number,
   * date and customer.
   *
   * <p>Every criterion is optional, and a null one has to leave the query as
   * though it were not there. A plain clause cannot do that -- {@code $regex}
   * rejects a null outright, and {@code $in} rejects anything but an array, both
   * at parse time, so an {@code $or} guard beside them does not save it. Written
   * as an aggregation expression they are evaluated rather than matched, and a
   * null guard short-circuits before the operator is reached.
   *
   * <p>{@code $ifNull} covers the sales that have no invoice number -- an open
   * or cancelled cart -- which a regex could otherwise never match, and which
   * belong in an unfiltered list.
   *
   * @param invoiceNo matched as a case-insensitive substring; quote it if it may
   *     contain a regex character
   * @param soldFrom inclusive lower bound on {@code soldAt}
   * @param soldTo exclusive upper bound, so a caller passing the day after
   *     covers the whole of the last day
   * @param customerIds restricts to these customers when given
   */
  @Query("{ 'shopId': ?0, $expr: { $and: ["
      + "  { $or: [ { $eq: [?1, null] }, { $regexMatch: {"
      + "      input: { $ifNull: ['$invoiceNo', ''] }, regex: ?1, options: 'i' } } ] },"
      + "  { $or: [ { $eq: [?2, null] }, { $gte: ['$soldAt', ?2] } ] },"
      + "  { $or: [ { $eq: [?3, null] }, { $lt:  ['$soldAt', ?3] } ] },"
      + "  { $or: [ { $eq: [?4, null] }, { $in:  ['$customerId', ?4] } ] }"
      + "] } }")
  Page<Purchase> search(
      String shopId,
      String invoiceNo,
      Instant soldFrom,
      Instant soldTo,
      Collection<String> customerIds,
      Pageable pageable);
}

