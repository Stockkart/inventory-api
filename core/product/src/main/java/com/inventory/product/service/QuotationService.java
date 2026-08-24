package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.DocumentTypes;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.DocumentType;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.mapper.PurchaseMapper;
import com.inventory.product.rest.dto.request.AddToCartRequest;
import com.inventory.product.rest.dto.request.CreateQuotationRequest;
import com.inventory.product.rest.dto.response.AddToCartResponse;
import com.inventory.product.rest.dto.response.QuotationListResponse;
import com.inventory.product.rest.dto.response.QuotationSummaryDto;
import com.inventory.product.util.PurchaseItemRefs;
import com.inventory.product.service.vertical.QuotationCreateOrchestrator;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.utils.constants.ProductMetricsConstants;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.service.CustomerService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Open quotations (CREATED purchases) for multi-customer scan-sell. */
@Service
@Slf4j
@RequiredArgsConstructor
public class QuotationService {

  private static final int MAX_OPEN_QUOTATIONS_PER_USER = 30;

  private final PurchaseRepository purchaseRepository;
  private final PurchaseMapper purchaseMapper;
  private final CustomerService customerService;
  private final QuotationCreateOrchestrator quotationCreateOrchestrator;
  private final MetricsWrapper metrics;

  @Transactional
  public QuotationListResponse listOpenQuotations(String userId, String shopId) {
    List<Purchase> purchases =
        purchaseRepository.findByUserIdAndShopIdAndStatusOrderByUpdatedAtDesc(
            userId, shopId, PurchaseStatus.CREATED);
    purchases =
        purchases.stream().filter(DocumentTypes::isSaleDocument).toList();
    for (Purchase purchase : purchases) {
      if (!StringUtils.hasText(purchase.getTokenNo())) {
        quotationCreateOrchestrator
            .ensureQuotationToken(shopId, purchase.getBusinessType())
            .ifPresent(
                token -> {
                  purchase.setTokenNo(token);
                  purchaseRepository.save(purchase);
                });
      }
    }
    List<QuotationSummaryDto> summaries = purchases.stream().map(this::toSummary).toList();
    return new QuotationListResponse(summaries);
  }

  @Transactional
  public AddToCartResponse createQuotation(
      CreateQuotationRequest request, String userId, String shopId) {
    validateCreateRequest(request);
    long openCount =
        purchaseRepository
            .findByUserIdAndShopIdAndStatusOrderByUpdatedAtDesc(
                userId, shopId, PurchaseStatus.CREATED)
            .stream()
            .filter(DocumentTypes::isSaleDocument)
            .count();
    if (openCount >= MAX_OPEN_QUOTATIONS_PER_USER) {
      throw new ValidationException(
          "Maximum open quotations reached (" + MAX_OPEN_QUOTATIONS_PER_USER + "). Cancel one to continue.");
    }

    AddToCartRequest cartRequest = toAddToCartRequest(request);
    String customerId = resolveCustomerId(shopId, cartRequest);
    String customerName = resolveCustomerName(customerId, cartRequest);

    try {
      Purchase purchase =
          purchaseMapper.toPurchaseForCart(
              cartRequest,
              new ArrayList<>(),
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              shopId,
              userId,
              customerId,
              BillingMode.REGULAR);
      purchase.setDocumentType(DocumentType.SALE);
      purchase.setSaleAdditionalDiscountTotal(BigDecimal.ZERO);
      purchase.setSgstAmount(BigDecimal.ZERO);
      purchase.setCgstAmount(BigDecimal.ZERO);
      if (StringUtils.hasText(customerName)) {
        purchase.setCustomerName(customerName);
      }
      quotationCreateOrchestrator
          .allocateTokenForNewQuotation(shopId, request.getBusinessType())
          .ifPresent(purchase::setTokenNo);
      purchase = purchaseRepository.save(purchase);
      log.info("Created quotation {} for shop {}", purchase.getId(), shopId);
      metrics.record(
          ProductMetricsConstants.QUOTATIONS_TOTAL,
          1,
          "module",
          ProductMetricsConstants.MODULE);
      return purchaseMapper.toAddToCartResponse(purchase);
    } catch (DataAccessException e) {
      log.error("Database error creating quotation for shop {}", shopId, e);
      throw new BaseException(
          ErrorCode.INTERNAL_SERVER_ERROR, "Error creating quotation: " + e.getMessage(), e);
    }
  }

  @Transactional
  public void cancelQuotation(String purchaseId, String userId, String shopId) {
    Purchase purchase =
        purchaseRepository
            .findByIdAndUserIdAndShopId(purchaseId, userId, shopId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Quotation", "purchaseId", purchaseId));
    if (DocumentTypes.isEstimate(purchase)) {
      throw new ValidationException("Use discard estimate for estimate documents");
    }
    if (purchase.getStatus() != PurchaseStatus.CREATED) {
      throw new ValidationException("Only open quotations can be cancelled");
    }
    purchase.setStatus(PurchaseStatus.CANCELLED);
    purchase.setUpdatedAt(Instant.now());
    purchaseRepository.save(purchase);
    log.info("Cancelled quotation {} for shop {}", purchaseId, shopId);
  }

  @Transactional(readOnly = true)
  public AddToCartResponse getQuotation(String purchaseId, String userId, String shopId) {
    Purchase purchase = loadOpenOrPendingQuotation(purchaseId, userId, shopId);
    return purchaseMapper.toAddToCartResponse(purchase);
  }

  /**
   * Resolves the cart to update for upsert. Returns null when a new quotation should be created.
   */
  @Transactional(readOnly = true)
  public Purchase resolveTargetCart(AddToCartRequest request, String userId, String shopId) {
    if (Boolean.TRUE.equals(request.getCreateNewQuotation())) {
      return null;
    }
    if (StringUtils.hasText(request.getPurchaseId())) {
      Purchase purchase =
          purchaseRepository
              .findById(request.getPurchaseId().trim())
              .orElseThrow(
                  () ->
                      new ResourceNotFoundException(
                          "Quotation", "purchaseId", request.getPurchaseId()));
      if (!shopId.equals(purchase.getShopId())) {
        throw new ValidationException("Document does not belong to the authenticated shop");
      }
      if (DocumentTypes.isEstimate(purchase)) {
        if (purchase.getStatus() != PurchaseStatus.CREATED) {
          throw new ValidationException(
              "Cannot modify estimate in status " + purchase.getStatus());
        }
        if (purchase.getEstimateState()
            != com.inventory.product.domain.model.enums.EstimateState.OPEN) {
          throw new ValidationException(
              "Cannot modify estimate in state " + purchase.getEstimateState());
        }
        return purchase;
      }
      if (!userId.equals(purchase.getUserId())) {
        throw new ValidationException("Quotation does not belong to the authenticated user");
      }
      if (purchase.getStatus() != PurchaseStatus.CREATED) {
        throw new ValidationException(
            "Cannot modify quotation in status " + purchase.getStatus());
      }
      return purchase;
    }
    List<Purchase> open =
        purchaseRepository.findByUserIdAndShopIdAndStatusOrderByUpdatedAtDesc(
            userId, shopId, PurchaseStatus.CREATED);
    return open.stream()
        .filter(DocumentTypes::isSaleDocument)
        .findFirst()
        .orElse(null);
  }

  /** Sum of base quantities reserved in other open SALE quotations for the shop. Estimates do not reserve. */
  @Transactional(readOnly = true)
  public Map<String, Integer> quotedBaseQuantitiesByLot(String shopId, String excludePurchaseId) {
    List<Purchase> open =
        purchaseRepository.findByShopIdAndStatus(shopId, PurchaseStatus.CREATED);
    Map<String, Integer> reserved = new HashMap<>();
    for (Purchase purchase : open) {
      if (excludePurchaseId != null && excludePurchaseId.equals(purchase.getId())) {
        continue;
      }
      if (DocumentTypes.isEstimate(purchase)) {
        continue;
      }
      if (purchase.getItems() == null) {
        continue;
      }
      for (PurchaseItem item : purchase.getItems()) {
        if ("menu".equalsIgnoreCase(item.getSellMode())) {
          continue;
        }
        PurchaseItemRefs.normalize(item);
        String lotId = PurchaseItemRefs.stockLotId(item);
        if (!StringUtils.hasText(lotId)) {
          continue;
        }
        int baseQty = baseQuantityOrZero(item);
        reserved.merge(lotId, baseQty, Integer::sum);
      }
    }
    return reserved;
  }

  @Transactional(readOnly = true)
  public Optional<AddToCartResponse> findLegacyActiveCart(String userId, String shopId) {
    // List query — Optional findByStatus throws if more than one PENDING checkout exists.
    List<Purchase> pending =
        purchaseRepository.findByUserIdAndShopIdAndStatusOrderByUpdatedAtDesc(
            userId, shopId, PurchaseStatus.PENDING);
    Optional<Purchase> pendingSale =
        pending.stream().filter(DocumentTypes::isSaleDocument).findFirst();
    if (pendingSale.isPresent()) {
      return pendingSale.map(purchaseMapper::toAddToCartResponse);
    }
    List<Purchase> created =
        purchaseRepository.findByUserIdAndShopIdAndStatusOrderByUpdatedAtDesc(
            userId, shopId, PurchaseStatus.CREATED);
    return created.stream()
        .filter(DocumentTypes::isSaleDocument)
        .findFirst()
        .map(purchaseMapper::toAddToCartResponse);
  }

  private Purchase loadOpenOrPendingQuotation(String purchaseId, String userId, String shopId) {
    Purchase purchase =
        purchaseRepository
            .findById(purchaseId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Quotation", "purchaseId", purchaseId));
    if (!shopId.equals(purchase.getShopId())) {
      throw new ValidationException("Document does not belong to the authenticated shop");
    }
    if (DocumentTypes.isEstimate(purchase)) {
      // Estimates are shop-scoped; any shop user may load for print / edit (when OPEN).
      if (purchase.getStatus() != PurchaseStatus.CREATED
          && purchase.getStatus() != PurchaseStatus.CANCELLED) {
        throw new ValidationException(
            "Estimate is not available (status: " + purchase.getStatus() + ")");
      }
      return purchase;
    }
    if (!userId.equals(purchase.getUserId())) {
      throw new ValidationException("Quotation does not belong to the authenticated user");
    }
    if (purchase.getStatus() != PurchaseStatus.CREATED
        && purchase.getStatus() != PurchaseStatus.PENDING) {
      throw new ValidationException(
          "Quotation is not open (status: " + purchase.getStatus() + ")");
    }
    return purchase;
  }

  private QuotationSummaryDto toSummary(Purchase purchase) {
    int itemCount =
        purchase.getItems() != null ? purchase.getItems().size() : 0;
    String phone = null;
    String name = purchase.getCustomerName();
    if (StringUtils.hasText(purchase.getCustomerId())) {
      var customerOpt = customerService.getCustomerById(purchase.getCustomerId());
      if (customerOpt.isPresent()) {
        Customer customer = customerOpt.get();
        if (!customer.isGeneralCustomer()) {
          phone = customer.getPhone();
          if (!StringUtils.hasText(name)) {
            name = customer.getName();
          }
        }
      }
    }
    if (!StringUtils.hasText(name)) {
      name = phone;
    }
    if (!StringUtils.hasText(name)) {
      name = "Walk-in";
    }
    return new QuotationSummaryDto(
        purchase.getId(),
        purchase.getStatus(),
        purchase.getCustomerId(),
        name,
        phone,
        purchase.getTokenNo(),
        itemCount,
        purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO,
        purchase.getCreatedAt(),
        purchase.getUpdatedAt());
  }

  private void validateCreateRequest(CreateQuotationRequest request) {
    if (request == null) {
      throw new ValidationException("Quotation request cannot be null");
    }
    if (!StringUtils.hasText(request.getBusinessType())) {
      throw new ValidationException("Business type is required");
    }
  }

  private AddToCartRequest toAddToCartRequest(CreateQuotationRequest request) {
    AddToCartRequest cartRequest = new AddToCartRequest();
    cartRequest.setBusinessType(request.getBusinessType());
    cartRequest.setCustomerId(request.getCustomerId());
    cartRequest.setCustomerName(request.getCustomerName());
    cartRequest.setCustomerAddress(request.getCustomerAddress());
    cartRequest.setCustomerPhone(request.getCustomerPhone());
    cartRequest.setCustomerEmail(request.getCustomerEmail());
    cartRequest.setCustomerGstin(request.getCustomerGstin());
    cartRequest.setCustomerDlNo(request.getCustomerDlNo());
    cartRequest.setCustomerPan(request.getCustomerPan());
    cartRequest.setCustomerPartyType(request.getCustomerPartyType());
    cartRequest.setCustomerUserId(request.getCustomerUserId());
    cartRequest.setItems(List.of());
    return cartRequest;
  }

  private String resolveCustomerId(String shopId, AddToCartRequest request) {
    return customerService.resolvePurchaseCustomerId(
        shopId, request.getCustomerId(), PurchaseCustomerRequests.fromCart(request));
  }

  private String resolveCustomerName(String customerId, AddToCartRequest request) {
    return PurchaseCustomerRequests.displayNameOverlay(customerId, request);
  }

  private static int baseQuantityOrZero(PurchaseItem item) {
    if (item.getBaseQuantity() != null && item.getBaseQuantity() > 0) {
      return item.getBaseQuantity();
    }
    if (item.getQuantity() != null) {
      return item.getQuantity().intValue();
    }
    return 0;
  }
}
