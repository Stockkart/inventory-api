package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.credit.domain.model.CreditPartyType;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.model.RefundItem;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.RefundRepository;
import com.inventory.product.rest.dto.request.RefundRequest;
import com.inventory.product.mapper.RefundMapper;
import com.inventory.product.util.MongoEmbeddedReadUtil;
import com.inventory.product.rest.dto.response.RefundListResponse;
import com.inventory.product.rest.dto.response.RefundResponse;
import com.inventory.product.rest.dto.response.RefundSummaryDto;
import com.inventory.product.rest.dto.response.RefundSummaryItemDto;
import com.inventory.product.utils.constants.ProductMetricsConstants;
import com.inventory.product.validation.CheckoutValidator;
import com.inventory.user.service.CustomerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for processing refunds.
 */
@Service
@Slf4j
@Transactional
public class RefundService {

  @Autowired
  private PurchaseRepository purchaseRepository;

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private RefundRepository refundRepository;

  @Autowired
  private CheckoutValidator checkoutValidator;

  @Autowired
  private RefundMapper refundMapper;

  @Autowired
  private CustomerService customerService;

  @Autowired
  private InvoiceSequenceService invoiceSequenceService;

  @Autowired(required = false)
  private com.inventory.metrics.MetricsWrapper metrics;

  @Autowired
  private com.inventory.user.domain.repository.CustomerRepository customerRepository;

  @Autowired
  private com.inventory.user.domain.repository.ShopCustomerRepository shopCustomerRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired(required = false)
  private com.inventory.accounting.api.AccountingFacade accountingFacade;

  @Autowired(required = false)
  private com.inventory.credit.service.CreditService creditService;

  /**
   * Process refund for a purchase.
   * Validates the purchase, calculates refund amount, and restores inventory.
   *
   * @param request refund request with purchaseId and items to refund
   * @param httpRequest HTTP request containing shopId and userId
   * @return RefundResponse with refund amount and refunded items
   */
  public RefundResponse processRefund(RefundRequest request, HttpServletRequest httpRequest) {
    // Get shopId and userId from request attributes
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");

    // Validate shopId and userId
    checkoutValidator.validateShopIdAndUserId(shopId, userId);

    // Validate refund request
    if (request == null) {
      throw new ValidationException("Refund request cannot be null");
    }
    if (!StringUtils.hasText(request.getPurchaseId())) {
      throw new ValidationException("Purchase ID is required");
    }
    if (request.getItems() == null || request.getItems().isEmpty()) {
      throw new ValidationException("Refund items list cannot be empty");
    }

    log.info("Processing refund for purchase ID: {}, shop: {}, user: {}",
        request.getPurchaseId(), shopId, userId);

    try {
      // Find purchase by ID
      Purchase purchase = purchaseRepository.findById(request.getPurchaseId())
          .orElseThrow(() -> new ResourceNotFoundException("Purchase", "id",
              "No purchase found with ID " + request.getPurchaseId()));

      // Verify purchase belongs to the shop
      if (!purchase.getShopId().equals(shopId)) {
        throw new ValidationException("Purchase does not belong to shop " + shopId);
      }

      // Verify purchase status is COMPLETED (can only refund completed purchases)
      if (purchase.getStatus() != PurchaseStatus.COMPLETED) {
        throw new ValidationException("Can only refund completed purchases. Current status: " + purchase.getStatus());
      }

      BillingMode billingMode =
          purchase.getBillingMode() != null ? purchase.getBillingMode() : BillingMode.REGULAR;
      Map<String, Integer> purchasedBaseQuantities = new HashMap<>();
      if (purchase.getItems() != null) {
        for (PurchaseItem purchasedItem : purchase.getItems()) {
          purchasedBaseQuantities.put(
              purchasedItem.getInventoryId(),
              purchasedItem.getBaseQuantity() != null ? purchasedItem.getBaseQuantity() : 0);
        }
      }
      Map<String, Integer> priorRefundedBase = priorRefundedBaseByInventory(request.getPurchaseId());

      List<RefundResponse.RefundedItem> refundedItems = new ArrayList<>();
      List<RefundItem> domainRefundItems = new ArrayList<>();
      List<SalesReturnValuation.LineAmounts> lineAmounts = new ArrayList<>();

      for (RefundRequest.RefundItem refundItem : request.getItems()) {
        if (!StringUtils.hasText(refundItem.getInventoryId())) {
          throw new ValidationException("Inventory ID is required for all refund items");
        }
        if (refundItem.getQuantity() == null || refundItem.getQuantity() <= 0) {
          throw new ValidationException(
              "Refund quantity must be greater than 0 for inventory ID: "
                  + refundItem.getInventoryId());
        }

        Integer purchasedBaseQty = purchasedBaseQuantities.get(refundItem.getInventoryId());
        if (purchasedBaseQty == null) {
          throw new ValidationException(
              "Item with inventory ID "
                  + refundItem.getInventoryId()
                  + " was not found in purchase "
                  + request.getPurchaseId());
        }

        PurchaseItem purchaseItem =
            purchase.getItems().stream()
                .filter(item -> item.getInventoryId().equals(refundItem.getInventoryId()))
                .findFirst()
                .orElseThrow(
                    () ->
                        new ResourceNotFoundException(
                            "PurchaseItem",
                            "inventoryId",
                            "Purchase item not found for inventory ID: "
                                + refundItem.getInventoryId()));

        int refundBaseQuantity = getRefundBaseQuantity(purchaseItem, refundItem.getQuantity());
        int alreadyRefunded =
            priorRefundedBase.getOrDefault(refundItem.getInventoryId(), 0);
        if (alreadyRefunded + refundBaseQuantity > purchasedBaseQty) {
          throw new ValidationException(
              "Refund quantity exceeds remaining purchased quantity for inventory ID: "
                  + refundItem.getInventoryId());
        }

        SalesReturnValuation.LineAmounts amounts =
            SalesReturnValuation.lineAmounts(
                purchaseItem, refundBaseQuantity, refundItem.getQuantity(), billingMode);

        restoreInventoryForRefund(refundItem.getInventoryId(), refundBaseQuantity, shopId);

        BigDecimal priceToRetail =
            purchaseItem.getPriceToRetail() != null ? purchaseItem.getPriceToRetail() : BigDecimal.ZERO;

        RefundResponse.RefundedItem refundedItem = new RefundResponse.RefundedItem();
        refundedItem.setInventoryId(refundItem.getInventoryId());
        refundedItem.setName(purchaseItem.getName());
        refundedItem.setQuantity(refundItem.getQuantity());
        refundedItem.setPriceToRetail(priceToRetail);
        refundedItem.setItemRefundAmount(amounts.lineTotal());
        refundedItems.add(refundedItem);

        RefundItem domainItem = new RefundItem();
        domainItem.setInventoryId(refundItem.getInventoryId());
        domainItem.setName(purchaseItem.getName());
        domainItem.setQuantity(refundItem.getQuantity());
        domainItem.setPriceToRetail(priceToRetail);
        domainItem.setItemRefundAmount(amounts.lineTotal());
        domainItem.setTaxableValue(amounts.taxable());
        domainItem.setCgstAmount(amounts.cgst());
        domainItem.setSgstAmount(amounts.sgst());
        domainItem.setCogsAmount(amounts.cogs());
        domainItem.setLineReturnTotal(amounts.lineTotal());
        domainRefundItems.add(domainItem);
        lineAmounts.add(amounts);
      }

      SalesReturnValuation.AmountTotals totals = SalesReturnValuation.aggregate(lineAmounts);
      ReturnPaymentBreakdown.Result tender =
          ReturnPaymentBreakdown.resolve(
              totals.returnTotal(),
              request.getPaymentMethod(),
              request.getCashAmount(),
              request.getOnlineAmount(),
              request.getCreditAmount());

      Refund refund = new Refund();
      refund.setPurchaseId(request.getPurchaseId());
      refund.setShopId(shopId);
      refund.setUserId(userId);
      refund.setCreditNoteNo(invoiceSequenceService.getNextCreditNoteNo(shopId));
      refund.setRefundedItems(domainRefundItems);
      refund.setRefundAmount(totals.returnTotal());
      refund.setTaxableTotal(totals.taxableTotal());
      refund.setCgstAmount(totals.cgstTotal());
      refund.setSgstAmount(totals.sgstTotal());
      refund.setCogsTotal(totals.cogsTotal());
      refund.setRoundOff(totals.roundOff());
      refund.setCustomerId(purchase.getCustomerId());
      refund.setRefundCash(tender.refundCash());
      refund.setRefundOnline(tender.refundOnline());
      refund.setRefundToCredit(tender.refundToCredit());
      refund.setPaymentMethod(tender.paymentMethod());
      refund.setTotalItemsRefunded(refundedItems.size());
      refund.setReason(request.getReason());
      refund.setCreatedAt(Instant.now());
      refund.setUpdatedAt(Instant.now());

      refund = refundRepository.save(refund);

      postAccountingAndCreditForRefund(refund, purchase, totals, shopId, userId);

      log.info("Refund saved with credit note {} (id {}) for purchase ID: {}",
          refund.getCreditNoteNo(), refund.getId(), request.getPurchaseId());

      BigDecimal roundedAmount = totals.returnTotal();
      RefundResponse response =
          refundMapper.toRefundResponse(
              refund.getId(),
              refund.getCreditNoteNo(),
              request.getPurchaseId(),
              refundedItems,
              roundedAmount,
              refund.getCreatedAt());

      log.info(
          "Refund processed successfully for purchase ID: {}. Total refund amount: {}, Items refunded: {}",
          request.getPurchaseId(),
          roundedAmount,
          refundedItems.size());

      if (metrics != null) {
        metrics.record(
            ProductMetricsConstants.REFUNDS_TOTAL, 1, "module", ProductMetricsConstants.MODULE);
        if (roundedAmount.compareTo(BigDecimal.ZERO) > 0) {
          metrics.record(
              ProductMetricsConstants.REFUND_AMOUNT,
              roundedAmount.doubleValue(),
              "module",
              ProductMetricsConstants.MODULE);
        }
      }

      return response;

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Refund processing failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error during refund processing for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error processing refund: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error during refund processing for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred during refund processing: " + e.getMessage(), e);
    }
  }

  /**
   * Restore inventory for refunded items.
   * Increases currentCount and decreases soldCount.
   *
   * @param inventoryId the inventory ID to restore
   * @param quantity the quantity to restore
   * @param shopId the shop ID for validation
   */
  private void restoreInventoryForRefund(String inventoryId, Integer baseQuantity, String shopId) {
    // Find inventory by ID
    Inventory inventory = inventoryRepository.findById(inventoryId)
        .orElseThrow(() -> new ResourceNotFoundException("Inventory", "id",
            "Inventory not found with ID: " + inventoryId));

    // Verify inventory belongs to the same shop
    if (!shopId.equals(inventory.getShopId())) {
      throw new ValidationException("Inventory " + inventoryId +
          " does not belong to shop " + shopId);
    }

    // Get current values (handle nulls)
    int currentCount = getCurrentBaseCount(inventory);
    int soldCount = getSoldBaseCount(inventory);
    BigDecimal currentDisplayCount = getCurrentDisplayCount(inventory);
    BigDecimal soldDisplayCount = getSoldDisplayCount(inventory);
    BigDecimal displayQuantity = toDisplayQuantity(baseQuantity, inventory);

    // Validate soldCount is sufficient
    if (soldCount < baseQuantity) {
      throw new ValidationException("Cannot refund quantity " + baseQuantity +
          " for inventory " + inventoryId + ". Only " + soldCount + " items were sold.");
    }

    // Restore inventory counts (reverse of purchase completion)
    inventory.setCurrentCount(currentDisplayCount.add(displayQuantity).setScale(4, RoundingMode.HALF_UP));
    inventory.setSoldCount(soldDisplayCount.subtract(displayQuantity).setScale(4, RoundingMode.HALF_UP));
    inventory.setCurrentBaseCount(currentCount + baseQuantity);
    inventory.setSoldBaseCount(soldCount - baseQuantity);

    // Save updated inventory
    inventoryRepository.save(inventory);

    log.info("Restored inventory for refund: inventoryId={}, quantity={}, newCurrentCount={}, newSoldCount={}",
        inventoryId, baseQuantity, inventory.getCurrentBaseCount(), inventory.getSoldBaseCount());
  }

  private int getCurrentBaseCount(Inventory inventory) {
    if (inventory.getCurrentBaseCount() != null) {
      return inventory.getCurrentBaseCount();
    }
    if (inventory.getCurrentCount() == null) {
      return 0;
    }
    int factor = getDisplayToBaseFactor(inventory);
    return inventory.getCurrentCount()
        .multiply(BigDecimal.valueOf(factor))
        .setScale(0, RoundingMode.HALF_UP)
        .intValue();
  }

  private int getSoldBaseCount(Inventory inventory) {
    if (inventory.getSoldBaseCount() != null) {
      return inventory.getSoldBaseCount();
    }
    if (inventory.getSoldCount() == null) {
      return 0;
    }
    int factor = getDisplayToBaseFactor(inventory);
    return inventory.getSoldCount()
        .multiply(BigDecimal.valueOf(factor))
        .setScale(0, RoundingMode.HALF_UP)
        .intValue();
  }

  private BigDecimal getCurrentDisplayCount(Inventory inventory) {
    if (inventory.getCurrentCount() != null) {
      return inventory.getCurrentCount();
    }
    return toDisplayQuantity(getCurrentBaseCount(inventory), inventory);
  }

  private BigDecimal getSoldDisplayCount(Inventory inventory) {
    if (inventory.getSoldCount() != null) {
      return inventory.getSoldCount();
    }
    return toDisplayQuantity(getSoldBaseCount(inventory), inventory);
  }

  private BigDecimal toDisplayQuantity(int baseQuantity, Inventory inventory) {
    int factor = getDisplayToBaseFactor(inventory);
    return BigDecimal.valueOf(baseQuantity)
        .divide(BigDecimal.valueOf(factor), 4, RoundingMode.HALF_UP);
  }

  private int getDisplayToBaseFactor(Inventory inventory) {
    if (inventory.getUnitConversions() == null
        || inventory.getUnitConversions().getFactor() == null
        || inventory.getUnitConversions().getFactor() <= 0) {
      return 1;
    }
    return inventory.getUnitConversions().getFactor();
  }

  private void postAccountingAndCreditForRefund(
      Refund refund,
      Purchase purchase,
      SalesReturnValuation.AmountTotals totals,
      String shopId,
      String userId) {
    if (accountingFacade != null && refund != null) {
      java.time.LocalDate txnDate =
          refund.getCreatedAt() != null
              ? java.time.LocalDate.ofInstant(refund.getCreatedAt(), java.time.ZoneOffset.UTC)
              : java.time.LocalDate.now();
      com.inventory.accounting.api.SalesReturnPostingRequest req =
          com.inventory.accounting.api.SalesReturnPostingRequest.builder()
              .sourceId(refund.getId())
              .creditNoteNo(refund.getCreditNoteNo())
              .txnDate(txnDate)
              .customerId(refund.getCustomerId())
              .customerDisplayName(resolveCustomerDisplayName(purchase))
              .originalSaleId(purchase.getId())
              .originalInvoiceNo(purchase.getInvoiceNo())
              .taxableRevenue(totals.taxableTotal())
              .outputCgst(totals.cgstTotal())
              .outputSgst(totals.sgstTotal())
              .returnTotal(totals.returnTotal())
              .cogsAmount(totals.cogsTotal())
              .roundOff(totals.roundOff())
              .refundCash(refund.getRefundCash())
              .refundOnline(refund.getRefundOnline())
              .refundToCredit(refund.getRefundToCredit())
              .paymentMethod(refund.getPaymentMethod())
              .build();
      accountingFacade.postSalesReturn(shopId, userId, req);
    }

    if (creditService != null
        && refund != null
        && refund.getRefundToCredit() != null
        && refund.getRefundToCredit().signum() > 0
        && StringUtils.hasText(refund.getCustomerId())) {
      creditService.recordReturnDueReduction(
          shopId,
          userId,
          CreditPartyType.CUSTOMER,
          refund.getCustomerId().trim(),
          resolveCustomerDisplayName(purchase),
          refund.getRefundToCredit(),
          "REFUND",
          refund.getId(),
          "RETURN:CREDIT:" + refund.getId(),
          "Sales return (credit)"
              + (StringUtils.hasText(refund.getCreditNoteNo())
                  ? " · CN " + refund.getCreditNoteNo().trim()
                  : "")
              + (StringUtils.hasText(purchase.getInvoiceNo())
                  ? " · vs " + purchase.getInvoiceNo().trim()
                  : ""),
          refund.getCreatedAt() != null
              ? java.time.LocalDate.ofInstant(refund.getCreatedAt(), java.time.ZoneOffset.UTC)
              : java.time.LocalDate.now());
    }
  }

  private String resolveCustomerDisplayName(Purchase purchase) {
    if (purchase == null) {
      return "Customer";
    }
    if (StringUtils.hasText(purchase.getCustomerName())) {
      return purchase.getCustomerName().trim();
    }
    if (!StringUtils.hasText(purchase.getCustomerId())) {
      return "Customer";
    }
    return customerRepository
        .findById(purchase.getCustomerId().trim())
        .map(c -> StringUtils.hasText(c.getName()) ? c.getName().trim() : "Customer")
        .orElse("Customer");
  }

  private Map<String, Integer> priorRefundedBaseByInventory(String purchaseId) {
    Map<String, Integer> out = new HashMap<>();
    if (!StringUtils.hasText(purchaseId)) {
      return out;
    }
    Purchase purchase = purchaseRepository.findById(purchaseId).orElse(null);
    if (purchase == null) {
      return out;
    }
    for (Refund prior : refundRepository.findByPurchaseIdOrderByCreatedAtDesc(purchaseId)) {
      if (prior.getRefundedItems() == null) {
        continue;
      }
      for (RefundItem item : prior.getRefundedItems()) {
        if (!StringUtils.hasText(item.getInventoryId()) || item.getQuantity() == null) {
          continue;
        }
        PurchaseItem pi =
            purchase.getItems() == null
                ? null
                : purchase.getItems().stream()
                    .filter(p -> item.getInventoryId().equals(p.getInventoryId()))
                    .findFirst()
                    .orElse(null);
        if (pi == null) {
          continue;
        }
        int base = getRefundBaseQuantity(pi, item.getQuantity());
        out.merge(item.getInventoryId(), base, Integer::sum);
      }
    }
    return out;
  }

  private int getRefundBaseQuantity(PurchaseItem purchaseItem, Integer refundQuantity) {
    int purchasedBaseQuantity = purchaseItem.getBaseQuantity() != null ? purchaseItem.getBaseQuantity() : 0;
    BigDecimal purchasedPricingQuantity = purchaseItem.getQuantity() != null
        ? purchaseItem.getQuantity()
        : BigDecimal.ZERO;
    if (purchasedPricingQuantity.compareTo(BigDecimal.ZERO) <= 0 || purchasedBaseQuantity <= 0) {
      return refundQuantity;
    }
    BigDecimal ratio = BigDecimal.valueOf(purchasedBaseQuantity)
        .divide(purchasedPricingQuantity, 4, RoundingMode.HALF_UP);
    return ratio.multiply(BigDecimal.valueOf(refundQuantity))
        .setScale(0, RoundingMode.HALF_UP)
        .intValue();
  }

  /**
   * Get list of refunds with pagination and search support.
   * Supports searching by invoice number, customer phone, customer ID, and customer email.
   *
   * @param page page number (1-based)
   * @param limit page size
   * @param invoiceNo optional invoice number to search
   * @param customerPhone optional customer phone to search
   * @param customerId optional customer ID to search
   * @param customerEmail optional customer email to search
   * @param httpRequest HTTP request containing shopId
   * @return RefundListResponse with paginated refunds
   */
  @Transactional(readOnly = true)
  public RefundListResponse getRefunds(Integer page, Integer limit, String invoiceNo,
                                       String customerPhone, String customerId, String customerEmail,
                                       HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }

    log.info("Getting refunds for shop: {}, page: {}, limit: {}, invoiceNo: {}, customerPhone: {}, customerId: {}, customerEmail: {}",
        shopId, page, limit, invoiceNo, customerPhone, customerId, customerEmail);

    try {
      // Set defaults
      int pageNumber = (page != null && page > 0) ? page - 1 : 0; // Spring Data uses 0-based indexing
      int pageSize = (limit != null && limit > 0) ? limit : 20; // Default limit of 20

      // Validate page size (max 100 to prevent performance issues)
      if (pageSize > 100) {
        pageSize = 100;
        log.warn("Page size exceeded maximum, setting to 100");
      }

      // Create Pageable with sorting by createdAt descending
      Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));

      Page<Refund> refundPage;
      List<String> purchaseIds = null;

      // If search criteria provided, find matching purchase IDs first
      if (StringUtils.hasText(invoiceNo) || StringUtils.hasText(customerPhone) || 
          StringUtils.hasText(customerId) || StringUtils.hasText(customerEmail)) {
        purchaseIds = findPurchaseIdsBySearchCriteria(shopId, invoiceNo, customerPhone, customerId, customerEmail);

        if (purchaseIds.isEmpty()) {
          return refundMapper.toRefundListResponse(List.of(), pageNumber + 1, pageSize, 0, 0);
        }

        // Find refunds for matching purchase IDs
        refundPage = refundRepository.findByPurchaseIdIn(purchaseIds, pageable);
      } else {
        // No search criteria, get all refunds for the shop
        refundPage = refundRepository.findByShopId(shopId, pageable);
      }

      List<String> refundIdsForRawFallback =
          refundPage.getContent().stream()
              .filter(
                  r ->
                      r.getRefundedItems() == null || r.getRefundedItems().isEmpty())
              .map(Refund::getId)
              .filter(StringUtils::hasText)
              .distinct()
              .collect(Collectors.toList());

      Map<String, Document> rawRefundById =
          MongoEmbeddedReadUtil.documentsByShopAndIds(
              mongoTemplate, "refunds", shopId, refundIdsForRawFallback);

      List<RefundSummaryDto> refundDtos =
          refundPage.getContent().stream()
              .map(r -> toRefundSummaryDto(r, rawRefundById.get(r.getId())))
              .collect(Collectors.toList());

      RefundListResponse response = refundMapper.toRefundListResponse(
          refundDtos, pageNumber + 1, pageSize,
          refundPage.getTotalElements(), refundPage.getTotalPages());

      log.info("Retrieved {} refunds (page {} of {}) for shop: {}",
          refundDtos.size(), pageNumber + 1, refundPage.getTotalPages(), shopId);

      return response;

    } catch (DataAccessException e) {
      log.error("Database error while getting refunds for shop: {}", shopId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error getting refunds: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error while getting refunds for shop: {}", shopId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while getting refunds: " + e.getMessage(), e);
    }
  }

  /**
   * Find purchase IDs by search criteria (invoice number, customer phone, customer ID, customer email).
   *
   * @param shopId the shop ID
   * @param invoiceNo optional invoice number
   * @param customerPhone optional customer phone
   * @param customerId optional customer ID
   * @param customerEmail optional customer email
   * @return list of purchase IDs matching the criteria
   */
  private List<String> findPurchaseIdsBySearchCriteria(String shopId, String invoiceNo,
                                                        String customerPhone, String customerId, String customerEmail) {
    List<String> purchaseIds = new ArrayList<>();

    // If customer phone provided, find customer IDs first
    if (StringUtils.hasText(customerPhone)) {
      java.util.Optional<com.inventory.user.domain.model.Customer> customerOpt =
          customerService.searchCustomerByPhone(customerPhone.trim(), shopId);
      if (customerOpt.isPresent()) {
        String foundCustomerId = customerOpt.get().getId();
        // If customerId was also provided, verify they match
        if (StringUtils.hasText(customerId) && !foundCustomerId.equals(customerId)) {
          // Mismatch, return empty list
          return new ArrayList<>();
        }
        customerId = foundCustomerId; // Use found customer ID
      } else {
        // Customer not found by phone, return empty list
        return new ArrayList<>();
      }
    }

    // If customer email provided, find customer IDs first
    if (StringUtils.hasText(customerEmail)) {
      java.util.Optional<com.inventory.user.domain.model.Customer> customerOpt =
          customerRepository.findByEmail(customerEmail.trim());
      if (customerOpt.isPresent()) {
        com.inventory.user.domain.model.Customer customer = customerOpt.get();
        // Verify customer is linked to the shop
        if (!shopCustomerRepository.existsByShopIdAndCustomerId(shopId, customer.getId())) {
          // Customer not linked to shop, return empty list
          return new ArrayList<>();
        }
        String foundCustomerId = customer.getId();
        // If customerId was also provided, verify they match
        if (StringUtils.hasText(customerId) && !foundCustomerId.equals(customerId)) {
          // Mismatch, return empty list
          return new ArrayList<>();
        }
        customerId = foundCustomerId; // Use found customer ID
      } else {
        // Customer not found by email, return empty list
        return new ArrayList<>();
      }
    }

    // Find purchases matching criteria
    List<Purchase> purchases;
    if (StringUtils.hasText(invoiceNo) && StringUtils.hasText(customerId)) {
      // Search by both invoice number and customer ID
      purchases = purchaseRepository.findByShopIdAndInvoiceNoAndCustomerId(shopId, invoiceNo.trim(), customerId);
    } else if (StringUtils.hasText(invoiceNo)) {
      // Search by invoice number only
      purchases = purchaseRepository.findByShopIdAndInvoiceNo(shopId, invoiceNo.trim());
    } else if (StringUtils.hasText(customerId)) {
      // Search by customer ID only
      purchases = purchaseRepository.findByShopIdAndCustomerId(shopId, customerId);
    } else {
      // No valid search criteria
      return new ArrayList<>();
    }

    // Extract purchase IDs
    purchaseIds = purchases.stream()
        .map(Purchase::getId)
        .distinct()
        .collect(Collectors.toList());

    return purchaseIds;
  }

  /**
   * Convert Refund entity to RefundSummaryDto.
   *
   * @param refund the refund entity
   * @return RefundSummaryDto
   */
  private RefundSummaryDto toRefundSummaryDto(Refund refund, Document rawFallback) {
    RefundSummaryDto dto = new RefundSummaryDto();
    dto.setRefundId(refund.getId());
    dto.setCreditNoteNo(refund.getCreditNoteNo());
    dto.setPurchaseId(refund.getPurchaseId());
    dto.setRefundAmount(refund.getRefundAmount());
    dto.setTotalItemsRefunded(refund.getTotalItemsRefunded());
    dto.setReason(refund.getReason());
    dto.setCreatedAt(refund.getCreatedAt());
    List<RefundItem> lineItems = resolveRefundedLineItems(refund, rawFallback);
    if (lineItems != null && !lineItems.isEmpty()) {
      dto.setRefundedItems(
          lineItems.stream().map(this::toRefundSummaryItemDto).collect(Collectors.toList()));
    }

    // Fetch purchase details to get invoice number and customer info
    purchaseRepository.findById(refund.getPurchaseId()).ifPresent(purchase -> {
      dto.setInvoiceNo(purchase.getInvoiceNo());
      dto.setCustomerId(purchase.getCustomerId());
      dto.setCustomerName(purchase.getCustomerName());

      // If customerId exists, fetch customer details
      if (StringUtils.hasText(purchase.getCustomerId())) {
        customerService.getCustomerById(purchase.getCustomerId()).ifPresent(customer -> {
          dto.setCustomerPhone(customer.getPhone());
          dto.setCustomerName(customer.getName());
          dto.setCustomerEmail(customer.getEmail());
        });
      }
    });

    return dto;
  }

  private List<RefundItem> resolveRefundedLineItems(Refund refund, Document rawFallback) {
    if (refund.getRefundedItems() != null && !refund.getRefundedItems().isEmpty()) {
      return refund.getRefundedItems();
    }
    return MongoEmbeddedReadUtil.refundLineItems(rawFallback);
  }

  private RefundSummaryItemDto toRefundSummaryItemDto(RefundItem item) {
    return new RefundSummaryItemDto(
        item.getInventoryId(),
        item.getName(),
        item.getQuantity(),
        item.getPriceToRetail(),
        item.getItemRefundAmount());
  }
}

