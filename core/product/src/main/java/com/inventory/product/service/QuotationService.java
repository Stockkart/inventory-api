package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.mapper.PurchaseMapper;
import com.inventory.product.rest.dto.request.AddToCartRequest;
import com.inventory.product.rest.dto.request.CreateQuotationRequest;
import com.inventory.product.rest.dto.response.AddToCartResponse;
import com.inventory.product.rest.dto.response.QuotationListResponse;
import com.inventory.product.rest.dto.response.QuotationSummaryDto;
import com.inventory.product.util.PurchaseItemRefs;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
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

  @Transactional(readOnly = true)
  public QuotationListResponse listOpenQuotations(String userId, String shopId) {
    List<Purchase> purchases =
        purchaseRepository.findByUserIdAndShopIdAndStatusOrderByUpdatedAtDesc(
            userId, shopId, PurchaseStatus.CREATED);
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
            .size();
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
      purchase.setSaleAdditionalDiscountTotal(BigDecimal.ZERO);
      purchase.setSgstAmount(BigDecimal.ZERO);
      purchase.setCgstAmount(BigDecimal.ZERO);
      if (StringUtils.hasText(customerName)) {
        purchase.setCustomerName(customerName);
      }
      purchase = purchaseRepository.save(purchase);
      log.info("Created quotation {} for shop {}", purchase.getId(), shopId);
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
              .findByIdAndUserIdAndShopId(request.getPurchaseId().trim(), userId, shopId)
              .orElseThrow(
                  () ->
                      new ResourceNotFoundException(
                          "Quotation", "purchaseId", request.getPurchaseId()));
      if (purchase.getStatus() != PurchaseStatus.CREATED) {
        throw new ValidationException(
            "Cannot modify quotation in status " + purchase.getStatus());
      }
      return purchase;
    }
    List<Purchase> open =
        purchaseRepository.findByUserIdAndShopIdAndStatusOrderByUpdatedAtDesc(
            userId, shopId, PurchaseStatus.CREATED);
    return open.isEmpty() ? null : open.get(0);
  }

  /** Sum of base quantities reserved in other open quotations for the shop. */
  @Transactional(readOnly = true)
  public Map<String, Integer> quotedBaseQuantitiesByLot(String shopId, String excludePurchaseId) {
    List<Purchase> open =
        purchaseRepository.findByShopIdAndStatus(shopId, PurchaseStatus.CREATED);
    Map<String, Integer> reserved = new HashMap<>();
    for (Purchase purchase : open) {
      if (excludePurchaseId != null && excludePurchaseId.equals(purchase.getId())) {
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
    Optional<Purchase> pending =
        purchaseRepository.findByUserIdAndShopIdAndStatus(
            userId, shopId, PurchaseStatus.PENDING);
    if (pending.isPresent()) {
      return Optional.of(purchaseMapper.toAddToCartResponse(pending.get()));
    }
    List<Purchase> created =
        purchaseRepository.findByUserIdAndShopIdAndStatusOrderByUpdatedAtDesc(
            userId, shopId, PurchaseStatus.CREATED);
    if (created.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(purchaseMapper.toAddToCartResponse(created.get(0)));
  }

  private Purchase loadOpenOrPendingQuotation(String purchaseId, String userId, String shopId) {
    Purchase purchase =
        purchaseRepository
            .findByIdAndUserIdAndShopId(purchaseId, userId, shopId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Quotation", "purchaseId", purchaseId));
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
        phone = customer.getPhone();
        if (!StringUtils.hasText(name)) {
          name = customer.getName();
        }
      }
    }
    if (!StringUtils.hasText(name)) {
      name = phone;
    }
    if (!StringUtils.hasText(name)) {
      name = "Quotation";
    }
    return new QuotationSummaryDto(
        purchase.getId(),
        purchase.getStatus(),
        purchase.getCustomerId(),
        name,
        phone,
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
    cartRequest.setCustomerName(request.getCustomerName());
    cartRequest.setCustomerAddress(request.getCustomerAddress());
    cartRequest.setCustomerPhone(request.getCustomerPhone());
    cartRequest.setCustomerEmail(request.getCustomerEmail());
    cartRequest.setCustomerGstin(request.getCustomerGstin());
    cartRequest.setCustomerDlNo(request.getCustomerDlNo());
    cartRequest.setCustomerPan(request.getCustomerPan());
    cartRequest.setCustomerUserId(request.getCustomerUserId());
    cartRequest.setItems(List.of());
    return cartRequest;
  }

  private String resolveCustomerId(String shopId, AddToCartRequest request) {
    boolean hasPhone = StringUtils.hasText(request.getCustomerPhone());
    boolean hasEmail = StringUtils.hasText(request.getCustomerEmail());
    boolean hasName = StringUtils.hasText(request.getCustomerName());

    if ((hasPhone || hasEmail) && hasName) {
      CreateCustomerRequest createCustomerRequest = new CreateCustomerRequest();
      createCustomerRequest.setName(request.getCustomerName());
      createCustomerRequest.setPhone(request.getCustomerPhone());
      createCustomerRequest.setAddress(request.getCustomerAddress());
      createCustomerRequest.setEmail(request.getCustomerEmail());
      createCustomerRequest.setGstin(request.getCustomerGstin());
      createCustomerRequest.setDlNo(request.getCustomerDlNo());
      createCustomerRequest.setPan(request.getCustomerPan());
      Customer customer = customerService.findOrCreateCustomer(shopId, createCustomerRequest);
      if (customer != null) {
        return customer.getId();
      }
    }
    if (hasPhone) {
      return customerService
          .searchCustomerByPhone(request.getCustomerPhone().trim(), shopId)
          .map(Customer::getId)
          .orElse(null);
    }
    return null;
  }

  private String resolveCustomerName(String customerId, AddToCartRequest request) {
    if (customerId == null
        && StringUtils.hasText(request.getCustomerName())
        && !StringUtils.hasText(request.getCustomerPhone())) {
      return request.getCustomerName().trim();
    }
    if (customerId == null && StringUtils.hasText(request.getCustomerPhone())) {
      return request.getCustomerPhone().trim();
    }
    return null;
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
