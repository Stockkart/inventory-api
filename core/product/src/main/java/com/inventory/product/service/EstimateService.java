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
import com.inventory.product.domain.model.enums.EstimateState;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.mapper.PurchaseMapper;
import com.inventory.product.rest.dto.request.AddToCartRequest;
import com.inventory.product.rest.dto.request.CreateEstimateRequest;
import com.inventory.product.rest.dto.response.AddToCartResponse;
import com.inventory.product.rest.dto.response.ConvertEstimateResponse;
import com.inventory.product.rest.dto.response.EstimateListResponse;
import com.inventory.product.rest.dto.response.EstimateSummaryDto;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.service.CustomerService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Estimate documents: printable quotes that do not soft-reserve stock and convert one-way into a
 * SALE cart for checkout.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EstimateService {

  private static final int MAX_OPEN_ESTIMATES_PER_SHOP = 100;

  private final PurchaseRepository purchaseRepository;
  private final PurchaseMapper purchaseMapper;
  private final CustomerService customerService;
  private final InvoiceSequenceService invoiceSequenceService;

  @Transactional(readOnly = true)
  public EstimateListResponse listEstimates(String shopId, EstimateState stateFilter) {
    List<Purchase> purchases;
    if (stateFilter != null) {
      purchases =
          purchaseRepository.findByShopIdAndDocumentTypeAndEstimateStateOrderByUpdatedAtDesc(
              shopId, DocumentType.ESTIMATE, stateFilter);
    } else {
      purchases =
          purchaseRepository.findByShopIdAndDocumentTypeOrderByUpdatedAtDesc(
              shopId, DocumentType.ESTIMATE);
    }
    List<EstimateSummaryDto> summaries =
        purchases.stream()
            .filter(p -> p.getEstimateState() != EstimateState.DISCARDED)
            .map(this::toSummary)
            .toList();
    return new EstimateListResponse(summaries);
  }

  @Transactional
  public AddToCartResponse createEstimate(
      CreateEstimateRequest request, String userId, String shopId) {
    validateCreateRequest(request);
    long openCount =
        purchaseRepository
            .findByShopIdAndDocumentTypeAndEstimateStateOrderByUpdatedAtDesc(
                shopId, DocumentType.ESTIMATE, EstimateState.OPEN)
            .size();
    if (openCount >= MAX_OPEN_ESTIMATES_PER_SHOP) {
      throw new ValidationException(
          "Maximum open estimates reached ("
              + MAX_OPEN_ESTIMATES_PER_SHOP
              + "). Discard or convert one to continue.");
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
      purchase.setDocumentType(DocumentType.ESTIMATE);
      purchase.setEstimateState(EstimateState.OPEN);
      purchase.setEstimateNo(invoiceSequenceService.getNextEstimateNo(shopId));
      purchase.setSaleAdditionalDiscountTotal(BigDecimal.ZERO);
      purchase.setSgstAmount(BigDecimal.ZERO);
      purchase.setCgstAmount(BigDecimal.ZERO);
      if (StringUtils.hasText(customerName)) {
        purchase.setCustomerName(customerName);
      }
      purchase = purchaseRepository.save(purchase);
      log.info(
          "Created estimate {} ({}) for shop {}",
          purchase.getId(),
          purchase.getEstimateNo(),
          shopId);
      return purchaseMapper.toAddToCartResponse(purchase);
    } catch (DataAccessException e) {
      log.error("Database error creating estimate for shop {}", shopId, e);
      throw new BaseException(
          ErrorCode.INTERNAL_SERVER_ERROR, "Error creating estimate: " + e.getMessage(), e);
    }
  }

  @Transactional
  public void discardEstimate(String purchaseId, String userId, String shopId) {
    Purchase purchase = loadEstimate(purchaseId, shopId);
    if (purchase.getEstimateState() == EstimateState.CONVERTED) {
      throw new ValidationException("Converted estimates cannot be discarded");
    }
    if (purchase.getEstimateState() == EstimateState.DISCARDED) {
      return;
    }
    purchase.setEstimateState(EstimateState.DISCARDED);
    purchase.setStatus(PurchaseStatus.CANCELLED);
    purchase.setUpdatedAt(Instant.now());
    purchaseRepository.save(purchase);
    log.info("Discarded estimate {} for shop {}", purchaseId, shopId);
  }

  /**
   * Locks the estimate as CONVERTED and clones lines into a new SALE quotation (CREATED). The sale
   * cart soft-reserves stock like any other open quotation.
   */
  @Transactional
  public ConvertEstimateResponse convertToSale(String estimateId, String userId, String shopId) {
    Purchase estimate = loadEstimate(estimateId, shopId);
    if (estimate.getEstimateState() != EstimateState.OPEN) {
      throw new ValidationException(
          "Only open estimates can be converted (state: " + estimate.getEstimateState() + ")");
    }
    if (estimate.getItems() == null || estimate.getItems().isEmpty()) {
      throw new ValidationException("Cannot convert an empty estimate");
    }

    long openSales =
        purchaseRepository
            .findByUserIdAndShopIdAndStatusOrderByUpdatedAtDesc(
                userId, shopId, PurchaseStatus.CREATED)
            .stream()
            .filter(DocumentTypes::isSaleDocument)
            .count();
    if (openSales >= 30) {
      throw new ValidationException(
          "Maximum open sales reached (30). Complete or cancel one before converting.");
    }

    Purchase sale = cloneAsSaleCart(estimate, userId);
    sale = purchaseRepository.save(sale);

    estimate.setEstimateState(EstimateState.CONVERTED);
    estimate.setConvertedToPurchaseId(sale.getId());
    estimate.setUpdatedAt(Instant.now());
    purchaseRepository.save(estimate);

    log.info(
        "Converted estimate {} → sale cart {} for shop {}",
        estimateId,
        sale.getId(),
        shopId);
    return new ConvertEstimateResponse(estimate.getId(), estimate.getEstimateNo(), sale.getId());
  }

  @Transactional(readOnly = true)
  public AddToCartResponse getEstimate(String purchaseId, String shopId) {
    return purchaseMapper.toAddToCartResponse(loadEstimate(purchaseId, shopId));
  }

  /** Ensures estimate edits only happen while OPEN. */
  public void assertEditableEstimate(Purchase purchase) {
    if (!DocumentTypes.isEstimate(purchase)) {
      return;
    }
    if (purchase.getEstimateState() != EstimateState.OPEN) {
      throw new ValidationException(
          "Cannot modify estimate in state " + purchase.getEstimateState());
    }
  }

  private Purchase cloneAsSaleCart(Purchase estimate, String userId) {
    Purchase sale = new Purchase();
    sale.setBusinessType(estimate.getBusinessType());
    sale.setBillingMode(
        estimate.getBillingMode() != null ? estimate.getBillingMode() : BillingMode.REGULAR);
    sale.setDocumentType(DocumentType.SALE);
    sale.setSourceEstimateId(estimate.getId());
    sale.setUserId(userId);
    sale.setShopId(estimate.getShopId());
    sale.setCustomerId(estimate.getCustomerId());
    sale.setCustomerName(estimate.getCustomerName());
    sale.setStatus(PurchaseStatus.CREATED);
    sale.setValid(true);
    sale.setItems(cloneItems(estimate.getItems()));
    sale.setSubTotal(estimate.getSubTotal());
    sale.setTaxTotal(estimate.getTaxTotal());
    sale.setSgstAmount(estimate.getSgstAmount());
    sale.setCgstAmount(estimate.getCgstAmount());
    sale.setDiscountTotal(estimate.getDiscountTotal());
    sale.setSaleAdditionalDiscountTotal(estimate.getSaleAdditionalDiscountTotal());
    sale.setGrandTotal(estimate.getGrandTotal());
    sale.setTotalCost(estimate.getTotalCost());
    sale.setRevenueBeforeTax(estimate.getRevenueBeforeTax());
    sale.setRevenueAfterTax(estimate.getRevenueAfterTax());
    sale.setTotalProfit(estimate.getTotalProfit());
    sale.setMarginPercent(estimate.getMarginPercent());
    Instant now = Instant.now();
    sale.setCreatedAt(now);
    sale.setUpdatedAt(now);
    sale.setSoldAt(now);
    return sale;
  }

  private static List<PurchaseItem> cloneItems(List<PurchaseItem> source) {
    List<PurchaseItem> cloned = new ArrayList<>();
    if (source == null) {
      return cloned;
    }
    for (PurchaseItem item : source) {
      PurchaseItem copy = new PurchaseItem();
      BeanUtils.copyProperties(item, copy);
      cloned.add(copy);
    }
    return cloned;
  }

  private Purchase loadEstimate(String purchaseId, String shopId) {
    Purchase purchase =
        purchaseRepository
            .findById(purchaseId)
            .orElseThrow(() -> new ResourceNotFoundException("Estimate", "purchaseId", purchaseId));
    if (!shopId.equals(purchase.getShopId())) {
      throw new ValidationException("Estimate does not belong to the specified shop");
    }
    if (!DocumentTypes.isEstimate(purchase)) {
      throw new ValidationException("Purchase is not an estimate");
    }
    return purchase;
  }

  private EstimateSummaryDto toSummary(Purchase purchase) {
    int itemCount = purchase.getItems() != null ? purchase.getItems().size() : 0;
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
    return new EstimateSummaryDto(
        purchase.getId(),
        purchase.getEstimateNo(),
        purchase.getStatus(),
        purchase.getEstimateState(),
        purchase.getBillingMode(),
        purchase.getCustomerId(),
        name,
        phone,
        itemCount,
        purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO,
        purchase.getConvertedToPurchaseId(),
        purchase.getCreatedAt(),
        purchase.getUpdatedAt());
  }

  private void validateCreateRequest(CreateEstimateRequest request) {
    if (request == null) {
      throw new ValidationException("Estimate request cannot be null");
    }
    if (!StringUtils.hasText(request.getBusinessType())) {
      throw new ValidationException("Business type is required");
    }
  }

  private AddToCartRequest toAddToCartRequest(CreateEstimateRequest request) {
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
}
