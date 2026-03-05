package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.InsufficientStockException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.AvailableUnit;
import com.inventory.product.domain.model.BillingMode;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.PurchaseStatus;
import com.inventory.product.domain.model.SchemeType;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.inventory.InventoryEventDto;
import com.inventory.product.rest.dto.sale.AddToCartRequest;
import com.inventory.product.rest.dto.sale.AddToCartResponse;
import com.inventory.product.rest.dto.sale.CheckoutResponse;
import com.inventory.product.rest.dto.sale.PurchaseListResponse;
import com.inventory.product.rest.dto.sale.PurchaseSummaryDto;
import com.inventory.product.rest.dto.sale.UpdatePurchaseStatusRequest;
import com.inventory.product.rest.mapper.InventoryMapper;
import com.inventory.product.rest.mapper.PurchaseMapper;
import com.inventory.plan.rest.dto.plan.RecordUsageRequest;
import com.inventory.plan.service.UsageService;
import com.inventory.product.validation.CheckoutValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
@Transactional(readOnly = true)
public class CheckoutService {

  /**
   * Billable quantity as decimal for amount calculations. Applies scheme as a ratio on any quantity.
   * - PERCENTAGE: full quantity (scheme applied on price).
   * - FIXED_UNITS: quantity * schemePayFor / (schemePayFor + schemeFree), so e.g. 19+1 → pay 95% of qty.
   * - No scheme: full quantity.
   */
  private BigDecimal getBillableQuantityAsDecimal(PurchaseItem item) {
    BigDecimal totalQty = getQuantityAsPricingUnits(item);
    if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    if (item.getSchemeType() == SchemeType.PERCENTAGE) {
      return totalQty;
    }
    if (item.getSchemeType() == SchemeType.FIXED_UNITS && item.getSchemePayFor() != null && item.getSchemePayFor() > 0
        && item.getSchemeFree() != null && item.getSchemeFree() >= 0) {
      BigDecimal payFor = BigDecimal.valueOf(item.getSchemePayFor());
      BigDecimal free = BigDecimal.valueOf(item.getSchemeFree());
      BigDecimal sum = payFor.add(free);
      if (sum.compareTo(BigDecimal.ZERO) <= 0) {
        return totalQty;
      }
      return totalQty.multiply(payFor).divide(sum, 4, RoundingMode.HALF_UP);
    }
    return totalQty;
  }

  private BigDecimal getQuantityAsPricingUnits(PurchaseItem item) {
    if (item.getBaseQuantity() != null && item.getUnitFactor() != null && item.getUnitFactor() > 0) {
      return BigDecimal.valueOf(item.getBaseQuantity())
          .divide(BigDecimal.valueOf(item.getUnitFactor()), 4, RoundingMode.HALF_UP);
    }
    return item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
  }

  /**
   * Effective selling price per unit. When schemeType is PERCENTAGE, scheme is applied on price:
   * effectivePrice = priceToRetail * (1 - schemePercentage/100). E.g. 50% scheme on 100 → 50.
   */
  private BigDecimal getEffectiveSellingPricePerUnit(PurchaseItem item) {
    BigDecimal price = item.getPriceToRetail() != null ? item.getPriceToRetail() : BigDecimal.ZERO;
    if (item.getSchemeType() == SchemeType.PERCENTAGE && item.getSchemePercentage() != null
        && item.getSchemePercentage().signum() > 0) {
      BigDecimal pct = item.getSchemePercentage();
      return price.multiply(BigDecimal.ONE.subtract(pct.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)));
    }
    return price;
  }

  /** When PERCENTAGE: set schemePayFor/schemeFree to null. When FIXED_UNITS: set schemePercentage to null. */
  private void normalizeSchemeFields(PurchaseItem item) {
    if (item.getSchemeType() == SchemeType.PERCENTAGE) {
      item.setSchemePayFor(null);
      item.setSchemeFree(null);
    } else if (item.getSchemeType() == SchemeType.FIXED_UNITS) {
      item.setSchemePercentage(null);
    }
  }

  private BillingMode normalizeBillingMode(BillingMode billingMode) {
    return billingMode != null ? billingMode : BillingMode.REGULAR;
  }

  private BillingMode resolveInventoryBillingMode(Inventory inventory) {
    return normalizeBillingMode(inventory != null ? inventory.getBillingMode() : null);
  }

  private boolean isTaxApplicable(BillingMode billingMode) {
    return normalizeBillingMode(billingMode) == BillingMode.REGULAR;
  }

  private void applyItemTaxMode(PurchaseItem item, BillingMode billingMode) {
    item.setBillingMode(normalizeBillingMode(billingMode));
    if (!isTaxApplicable(billingMode)) {
      item.setCgst(null);
      item.setSgst(null);
    }
  }

  private BillingMode resolveAndValidateCartBillingMode(Purchase existingCart, List<PurchaseItem> newItems) {
    Set<BillingMode> observed = new HashSet<>();
    BillingMode existingCartMode = existingCart != null ? existingCart.getBillingMode() : null;

    List<PurchaseItem> existingItems = existingCart != null && existingCart.getItems() != null
        ? existingCart.getItems()
        : List.of();
    boolean hasExistingItems = !existingItems.isEmpty();
    if (hasExistingItems) {
      observed.add(normalizeBillingMode(existingCartMode));
      for (PurchaseItem item : existingItems) {
        observed.add(normalizeBillingMode(item.getBillingMode()));
      }
    }

    if (newItems != null) {
      for (PurchaseItem item : newItems) {
        observed.add(normalizeBillingMode(item.getBillingMode()));
      }
    }

    if (observed.size() > 1) {
      throw new ValidationException("Cannot mix REGULAR and BASIC inventory items in a single cart");
    }

    if (observed.isEmpty()) {
      return BillingMode.REGULAR;
    }
    return observed.iterator().next();
  }

  @Autowired
  private PurchaseRepository purchaseRepository;

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private PurchaseMapper purchaseMapper;

  @Autowired
  private CheckoutValidator checkoutValidator;

  @Autowired
  private com.inventory.user.service.CustomerService customerService;

  @Autowired
  private com.inventory.user.domain.repository.CustomerRepository customerRepository;

  @Autowired
  private com.inventory.user.domain.repository.ShopCustomerRepository shopCustomerRepository;

  @Autowired
  private ShopRepository shopRepository;

  @Autowired
  private com.inventory.notifications.service.EventService eventService;

  @Autowired
  private InventoryMapper inventoryMapper;

  @Autowired
  private InvoiceSequenceService invoiceSequenceService;

  @Autowired(required = false)
  private UsageService usageService;

  @Transactional
  public AddToCartResponse addToCart(AddToCartRequest request, HttpServletRequest httpRequest) {
    // Get shopId and userId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");

    // Validate shopId and userId
    checkoutValidator.validateShopIdAndUserId(shopId, userId);

    // Validate request
    checkoutValidator.validateAddToCartRequest(request);

    log.info("Adding items to cart for shop: {}, user: {}", shopId, userId);

    try {
      // Find existing cart (CREATED status)
      Purchase existingCart = purchaseRepository.findByUserIdAndShopIdAndStatus(userId, shopId, PurchaseStatus.CREATED)
          .orElse(null);

      // Get or create customer and get customerId/customerName
      String customerId = getOrCreateCustomerId(shopId, request);
      String customerName = null;
      // If only customer name provided (no phone), store it directly
      if (customerId == null && StringUtils.hasText(request.getCustomerName()) && !StringUtils.hasText(request.getCustomerPhone())) {
        customerName = request.getCustomerName().trim();
      }

      // Process new items
      List<PurchaseItem> newItems = processCartItems(request.getItems(), shopId);
      BillingMode cartBillingMode = resolveAndValidateCartBillingMode(existingCart, newItems);

      Purchase purchase;
      if (existingCart != null) {
        // Validate stock availability before updating cart (check final quantities)
        validateStockAvailabilityForCartUpdate(existingCart, newItems, shopId);

        // Update existing cart - merge items (including quantity-0 update-only items)
        log.info("Updating existing cart with ID: {}", existingCart.getId());
        purchase = updateCart(existingCart, newItems, request.getBusinessType(), customerId, customerName, cartBillingMode);
      } else {
        // New cart: only items with quantity > 0 can be added; quantity-0 items are for updating discount on existing cart only
        List<PurchaseItem> itemsToAdd = newItems.stream()
            .filter(i -> i.getQuantity() != null && i.getQuantity().compareTo(BigDecimal.ZERO) > 0)
            .toList();
        if (itemsToAdd.isEmpty() && !newItems.isEmpty()) {
          throw new ValidationException(
              "Additional discount, scheme, or selling price can only be updated for items already in the cart. Add items to create a cart first.");
        }
        log.info("Creating new cart");
        purchase = createCart(request, itemsToAdd, shopId, userId, customerId, customerName, cartBillingMode);
      }

      log.info("Successfully updated cart with ID: {}", purchase.getId());

      // Build response
      return purchaseMapper.toAddToCartResponse(purchase);

    } catch (ValidationException | InsufficientStockException | ResourceNotFoundException e) {
      log.warn("Add to cart failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error during add to cart for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error adding items to cart: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error during add to cart for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred during add to cart: " + e.getMessage(), e);
    }
  }

  @Transactional
  public CheckoutResponse updatePurchaseStatus(UpdatePurchaseStatusRequest request, HttpServletRequest httpRequest) {
    // Get shopId and userId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");

    // Validate shopId and userId
    checkoutValidator.validateShopIdAndUserId(shopId, userId);

    // Validate request
    checkoutValidator.validateUpdateStatusRequest(request);

    // Set default payment method to "CASH" if not provided
    if (!StringUtils.hasText(request.getPaymentMethod())) {
      request.setPaymentMethod("CASH");
    }

    log.info("Updating purchase status to {} for purchase ID: {}, shop: {}, user: {}",
        request.getStatus(), request.getPurchaseId(), shopId, userId);

    try {
      // Find purchase by ID
      Purchase purchase = purchaseRepository.findById(request.getPurchaseId())
          .orElseThrow(() -> new ResourceNotFoundException("Purchase", "id",
              "No purchase found with ID " + request.getPurchaseId()));

      // Verify purchase belongs to the user's shop
      if (!shopId.equals(purchase.getShopId()) || !userId.equals(purchase.getUserId())) {
        throw new ValidationException("Purchase does not belong to the authenticated user's shop");
      }

      // Validate status transition
      PurchaseStatus currentStatus = purchase.getStatus();
      PurchaseStatus requestedStatus = request.getStatus();

      checkoutValidator.validateStatusTransition(currentStatus, requestedStatus);

      // If status is being changed to COMPLETED, check plan limits, decrease inventory
      if (requestedStatus == PurchaseStatus.COMPLETED) {
        BigDecimal grandTotal = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO;
        if (usageService != null) {
          usageService.checkCanAddBill(shopId, grandTotal, 1);
        }
        log.info("Processing inventory updates for completed purchase ID: {}", purchase.getId());
        updateInventoryForCompletedPurchase(purchase);
        // Sale date for GSTR and reporting: when the sale was completed (invoice date)
        purchase.setSoldAt(Instant.now());
      }

      // Update status and payment method
      purchase.setStatus(requestedStatus);
      purchase.setPaymentMethod(request.getPaymentMethod());
      purchase.setUpdatedAt(Instant.now());
      purchase = purchaseRepository.save(purchase);

      // Record billing usage after successful completion
      if (requestedStatus == PurchaseStatus.COMPLETED && usageService != null) {
        BigDecimal grandTotal = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO;
        RecordUsageRequest usageReq = new RecordUsageRequest();
        usageReq.setBillingAmount(grandTotal);
        usageReq.setBillCount(1);
        usageService.recordUsage(shopId, usageReq);
      }

      log.info("Successfully updated purchase status from {} to {} for purchase ID: {}",
          currentStatus, requestedStatus, purchase.getId());

      // Build response
      return purchaseMapper.toCheckoutResponse(purchase);

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Update purchase status failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error during update purchase status for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error updating purchase status: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error during update purchase status for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred during update purchase status: " + e.getMessage(), e);
    }
  }

  @Transactional(readOnly = true)
  public AddToCartResponse getCart(HttpServletRequest httpRequest) {
    // Get shopId and userId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");

    // Validate shopId and userId
    checkoutValidator.validateShopIdAndUserId(shopId, userId);

    log.info("Getting cart for shop: {}, user: {}", shopId, userId);

    try {
      // Find cart with CREATED or PENDING status
      List<PurchaseStatus> statuses = List.of(PurchaseStatus.CREATED, PurchaseStatus.PENDING);
      Purchase cart = purchaseRepository.findByUserIdAndShopIdAndStatusIn(userId, shopId, statuses)
          .orElseThrow(() -> new ResourceNotFoundException("Cart", "userId and shopId",
              "No active cart found for user " + userId + " and shop " + shopId));

      log.info("Found cart with ID: {} and status: {}", cart.getId(), cart.getStatus());

      return purchaseMapper.toAddToCartResponse(cart);

    } catch (ResourceNotFoundException e) {
      log.warn("Cart not found for shop: {}, user: {}", shopId, userId);
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting cart for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error getting cart: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error while getting cart for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while getting cart: " + e.getMessage(), e);
    }
  }

  @Transactional(readOnly = true)
  public PurchaseListResponse getPurchases(Integer page, Integer limit, String order, HttpServletRequest httpRequest) {
    // Get shopId and userId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");

    // Validate shopId and userId
    checkoutValidator.validateShopIdAndUserId(shopId, userId);

    log.info("Getting purchases for shop: {}, user: {}, page: {}, limit: {}, order: {}",
        shopId, userId, page, limit, order);

    try {
      // Set defaults
      int pageNumber = (page != null && page > 0) ? page - 1 : 0; // Spring Data uses 0-based indexing
      int pageSize = (limit != null && limit > 0) ? limit : 20; // Default limit of 20

      // Validate page size (max 100 to prevent performance issues)
      if (pageSize > 100) {
        pageSize = 100;
        log.warn("Page size exceeded maximum, setting to 100");
      }

      // Parse order parameter (default: soldAt desc)
      Sort sort = parseSortOrder(order);

      // Create Pageable
      Pageable pageable = PageRequest.of(pageNumber, pageSize, sort);

      // Query purchases by shopId
      Page<Purchase> purchasePage = purchaseRepository.findByShopId(shopId, pageable);

      // Map to DTOs
      List<PurchaseSummaryDto> purchaseDtos = purchasePage.getContent().stream()
          .map(purchaseMapper::toPurchaseSummaryDto)
          .toList();

      // Build response
      PurchaseListResponse response = new PurchaseListResponse();
      response.setPurchases(purchaseDtos);
      response.setPage(pageNumber + 1); // Convert back to 1-based for API response
      response.setLimit(pageSize);
      response.setTotal(purchasePage.getTotalElements());
      response.setTotalPages(purchasePage.getTotalPages());

      log.info("Retrieved {} purchases (page {} of {}) for shop: {}",
          purchaseDtos.size(), pageNumber + 1, purchasePage.getTotalPages(), shopId);

      return response;

    } catch (DataAccessException e) {
      log.error("Database error while getting purchases for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error getting purchases: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error while getting purchases for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while getting purchases: " + e.getMessage(), e);
    }
  }

  /**
   * Search purchases with pagination and customer search support.
   * Supports searching by invoice number (regex), customer email, phone, and name (regex).
   *
   * @param page page number (1-based)
   * @param limit page size
   * @param invoiceNo optional invoice number regex pattern to search
   * @param customerEmail optional customer email to search
   * @param customerPhone optional customer phone to search
   * @param customerName optional customer name regex pattern to search
   * @param httpRequest HTTP request containing shopId
   * @return PurchaseListResponse with paginated purchases
   */
  @Transactional(readOnly = true)
  public PurchaseListResponse searchPurchases(Integer page, Integer limit, String invoiceNo,
                                              String customerEmail, String customerPhone, String customerName,
                                              HttpServletRequest httpRequest) {
    // Get shopId and userId from request attributes
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");

    // Validate shopId and userId
    checkoutValidator.validateShopIdAndUserId(shopId, userId);

    log.info("Searching purchases for shop: {}, user: {}, page: {}, limit: {}, invoiceNo: {}, customerEmail: {}, customerPhone: {}, customerName: {}",
        shopId, userId, page, limit, invoiceNo, customerEmail, customerPhone, customerName);

    try {
      // Set defaults
      int pageNumber = (page != null && page > 0) ? page - 1 : 0; // Spring Data uses 0-based indexing
      int pageSize = (limit != null && limit > 0) ? limit : 20; // Default limit of 20

      // Validate page size (max 100 to prevent performance issues)
      if (pageSize > 100) {
        pageSize = 100;
        log.warn("Page size exceeded maximum, setting to 100");
      }

      // Create Pageable with sorting by soldAt descending
      Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "soldAt"));

      Page<Purchase> purchasePage;
      List<String> customerIds = null;

      // If customer search criteria provided, find matching customer IDs first
      if (StringUtils.hasText(customerEmail) || StringUtils.hasText(customerPhone) || StringUtils.hasText(customerName)) {
        customerIds = findCustomerIdsBySearchCriteria(shopId, customerEmail, customerPhone, customerName);

        if (customerIds.isEmpty()) {
          // No matching customers found, return empty result
          PurchaseListResponse response = new PurchaseListResponse();
          response.setPurchases(new ArrayList<>());
          response.setPage(pageNumber + 1);
          response.setLimit(pageSize);
          response.setTotal(0);
          response.setTotalPages(0);
          return response;
        }
      }

      // Create final reference for use in lambda expressions
      final List<String> finalCustomerIds = customerIds;

      // Search purchases based on criteria
      if (StringUtils.hasText(invoiceNo) && finalCustomerIds != null && !finalCustomerIds.isEmpty()) {
        // Search by both invoice number (regex) and customer IDs
        // Filter purchases by invoiceNo regex first, then by customerIds
        List<Purchase> purchasesByInvoice = purchaseRepository.findByShopIdAndInvoiceNoRegex(shopId, invoiceNo.trim());
        // Filter by customerIds
        List<Purchase> filteredPurchases = purchasesByInvoice.stream()
            .filter(p -> finalCustomerIds.contains(p.getCustomerId()))
            .collect(java.util.stream.Collectors.toList());
        // Sort and paginate
        filteredPurchases.sort((a, b) -> {
          if (a.getSoldAt() == null && b.getSoldAt() == null) return 0;
          if (a.getSoldAt() == null) return 1;
          if (b.getSoldAt() == null) return -1;
          return b.getSoldAt().compareTo(a.getSoldAt()); // Descending
        });
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), filteredPurchases.size());
        List<Purchase> pagedPurchases = start < filteredPurchases.size() 
            ? filteredPurchases.subList(start, end) 
            : new ArrayList<>();
        purchasePage = new org.springframework.data.domain.PageImpl<>(
            pagedPurchases, pageable, filteredPurchases.size());
      } else if (StringUtils.hasText(invoiceNo)) {
        // Search by invoice number using regex (case-insensitive)
        List<Purchase> purchases = purchaseRepository.findByShopIdAndInvoiceNoRegex(shopId, invoiceNo.trim());
        // Sort by soldAt descending
        purchases.sort((a, b) -> {
          if (a.getSoldAt() == null && b.getSoldAt() == null) return 0;
          if (a.getSoldAt() == null) return 1;
          if (b.getSoldAt() == null) return -1;
          return b.getSoldAt().compareTo(a.getSoldAt()); // Descending
        });
        // Convert to page
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), purchases.size());
        List<Purchase> pagedPurchases = start < purchases.size() 
            ? purchases.subList(start, end) 
            : new ArrayList<>();
        purchasePage = new org.springframework.data.domain.PageImpl<>(
            pagedPurchases, pageable, purchases.size());
      } else if (finalCustomerIds != null && !finalCustomerIds.isEmpty()) {
        // Search by customer IDs only
        purchasePage = purchaseRepository.findByShopIdAndCustomerIdIn(shopId, finalCustomerIds, pageable);
      } else {
        // No search criteria, get all purchases for the shop
        purchasePage = purchaseRepository.findByShopId(shopId, pageable);
      }

      // Map to DTOs
      List<PurchaseSummaryDto> purchaseDtos = purchasePage.getContent().stream()
          .map(purchaseMapper::toPurchaseSummaryDto)
          .toList();

      // Build response
      PurchaseListResponse response = new PurchaseListResponse();
      response.setPurchases(purchaseDtos);
      response.setPage(pageNumber + 1); // Convert back to 1-based for API response
      response.setLimit(pageSize);
      response.setTotal(purchasePage.getTotalElements());
      response.setTotalPages(purchasePage.getTotalPages());

      log.info("Retrieved {} purchases (page {} of {}) for shop: {}",
          purchaseDtos.size(), pageNumber + 1, purchasePage.getTotalPages(), shopId);

      return response;

    } catch (DataAccessException e) {
      log.error("Database error while searching purchases for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error searching purchases: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error while searching purchases for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while searching purchases: " + e.getMessage(), e);
    }
  }

  /**
   * Find customer IDs by search criteria (email, phone, name regex).
   *
   * @param shopId the shop ID
   * @param customerEmail optional customer email
   * @param customerPhone optional customer phone
   * @param customerName optional customer name regex pattern
   * @return list of customer IDs matching the criteria
   */
  private List<String> findCustomerIdsBySearchCriteria(String shopId, String customerEmail,
                                                       String customerPhone, String customerName) {
    List<String> customerIds = new ArrayList<>();
    List<com.inventory.user.domain.model.Customer> customers = new ArrayList<>();

    // Search by email
    if (StringUtils.hasText(customerEmail)) {
      customerRepository.findByEmail(customerEmail.trim()).ifPresent(customer -> {
        if (shopCustomerRepository.existsByShopIdAndCustomerId(shopId, customer.getId())) {
          customers.add(customer);
        }
      });
    }

    // Search by phone
    if (StringUtils.hasText(customerPhone)) {
      customerService.searchCustomerByPhone(customerPhone.trim(), shopId).ifPresent(customer -> {
        if (!customers.contains(customer)) {
          customers.add(customer);
        }
      });
    }

    // Search by name (regex)
    if (StringUtils.hasText(customerName)) {
      // Use CustomerRepository searchByQuery which supports regex on name
      List<com.inventory.user.domain.model.Customer> nameMatches = customerRepository.searchByQuery(customerName.trim());
      // Filter by shop
      for (com.inventory.user.domain.model.Customer customer : nameMatches) {
        if (shopCustomerRepository.existsByShopIdAndCustomerId(shopId, customer.getId())) {
          if (!customers.contains(customer)) {
            customers.add(customer);
          }
        }
      }
    }

    // Extract customer IDs
    customerIds = customers.stream()
        .map(com.inventory.user.domain.model.Customer::getId)
        .distinct()
        .collect(java.util.stream.Collectors.toList());

    return customerIds;
  }

  private Sort parseSortOrder(String order) {
    if (order == null || order.trim().isEmpty()) {
      // Default: soldAt desc
      return Sort.by(Sort.Direction.DESC, "soldAt");
    }

    // Parse order string: "field:direction" or just "field" (defaults to desc)
    // Examples: "soldAt:desc", "soldAt:asc", "grandTotal:desc", "soldAt"
    String[] parts = order.split(":");
    String field = parts[0].trim();
    Sort.Direction direction = Sort.Direction.DESC; // Default direction

    if (parts.length > 1) {
      String dirStr = parts[1].trim().toLowerCase();
      if ("asc".equals(dirStr)) {
        direction = Sort.Direction.ASC;
      } else if ("desc".equals(dirStr)) {
        direction = Sort.Direction.DESC;
      }
    }

    // Validate field name (only allow certain fields for security)
    // Allowed fields: soldAt, grandTotal, invoiceNo
    if (!isValidSortField(field)) {
      log.warn("Invalid sort field: {}, using default (soldAt desc)", field);
      return Sort.by(Sort.Direction.DESC, "soldAt");
    }

    return Sort.by(direction, field);
  }

  private boolean isValidSortField(String field) {
    // Whitelist of allowed sort fields
    return "soldAt".equals(field) ||
        "grandTotal".equals(field) ||
        "invoiceNo".equals(field);
  }

  private List<PurchaseItem> processCartItems(List<AddToCartRequest.CartItem> items, String shopId) {
    List<PurchaseItem> purchaseItems = new ArrayList<>();

    for (AddToCartRequest.CartItem item : items) {
      try {
        // Validate item using CheckoutValidator
        checkoutValidator.validateCartItem(item);

      // Quantity 0 or null with only price/discount/scheme changes = update only (item must already be in cart)
      // Exclude when baseQuantity is non-zero: that indicates a quantity change (e.g. baseQuantity=-1 to remove 1)
      boolean hasSchemeChange = item.getSchemePayFor() != null || item.getSchemeFree() != null
          || item.getSchemeType() != null || item.getSchemePercentage() != null;
      boolean hasQuantityChange = (item.getBaseQuantity() != null && item.getBaseQuantity() != 0)
          || (item.getQuantity() != null && item.getQuantity() != 0);
      boolean updateOnly = (item.getQuantity() == null || item.getQuantity() == 0)
          && !hasQuantityChange
          && (item.getAdditionalDiscount() != null || hasSchemeChange || item.getPriceToRetail() != null);

        if (updateOnly) {
          // Verify inventory exists and belongs to shop; no stock check needed
          Inventory inventory = inventoryRepository.findById(item.getId())
              .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", item.getId()));
          if (!shopId.equals(inventory.getShopId())) {
            throw new ValidationException("Inventory lot " + item.getId() + " does not belong to shop " + shopId);
          }
          String saleUnit = normalizeSaleUnit(item.getUnit(), inventory);
          int saleUnitFactor = getConversionFactorToBase(inventory, saleUnit);
          int pricingFactor = getDisplayToBaseFactor(inventory);
          int baseQuantity = resolveRequestedBaseQuantity(item, inventory, saleUnit, saleUnitFactor);
          BigDecimal pricingQuantity = BigDecimal.valueOf(baseQuantity)
              .divide(BigDecimal.valueOf(pricingFactor), 4, RoundingMode.HALF_UP);
          BigDecimal maximumRetailPrice = inventory.getMaximumRetailPrice();
          // Pass null for priceToRetail when not in request so merge keeps existing line's price
          PurchaseItem purchaseItem = purchaseMapper.createPurchaseItem(
              item.getId(),
              inventory.getName(),
              pricingQuantity,
              maximumRetailPrice,
              item.getPriceToRetail(),
              BigDecimal.ZERO
          );
          if (item.getAdditionalDiscount() != null) {
            purchaseItem.setAdditionalDiscount(item.getAdditionalDiscount());
          }
          if (item.getSchemeType() != null) {
            purchaseItem.setSchemeType(item.getSchemeType());
          }
          if (item.getSchemePercentage() != null) {
            purchaseItem.setSchemePercentage(item.getSchemePercentage());
          }
          // Normalize: PERCENTAGE -> only schemeType/schemePercentage; FIXED_UNITS -> only schemePayFor/schemeFree, schemePercentage null
          if (item.getSchemeType() == SchemeType.PERCENTAGE) {
            purchaseItem.setSchemePayFor(null);
            purchaseItem.setSchemeFree(null);
          } else {
            if (item.getSchemePayFor() != null) purchaseItem.setSchemePayFor(item.getSchemePayFor());
            if (item.getSchemeFree() != null) purchaseItem.setSchemeFree(item.getSchemeFree());
            purchaseItem.setSchemePercentage(null);
          }
          purchaseItem.setSaleUnit(saleUnit);
          purchaseItem.setBaseQuantity(baseQuantity);
          purchaseItem.setUnitFactor(pricingFactor);
          purchaseItem.setAvailableUnits(mapAvailableUnits(inventory));
          applyItemTaxMode(purchaseItem, resolveInventoryBillingMode(inventory));
          purchaseItems.add(purchaseItem);
        } else if ((item.getQuantity() != null && item.getQuantity() < 0)
            || (item.getBaseQuantity() != null && item.getBaseQuantity() < 0)) {
          // For negative quantities (reduce/remove), verify lotId exists and belongs to the shop
          // Stock validation is not needed for removing items
          Inventory inventory = inventoryRepository.findById(item.getId())
              .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", item.getId()));

          if (!shopId.equals(inventory.getShopId())) {
            throw new ValidationException("Inventory lot " + item.getId() + " does not belong to shop " + shopId);
          }
          String saleUnit = normalizeSaleUnit(item.getUnit(), inventory);
          int factor = getConversionFactorToBase(inventory, saleUnit);
          int pricingFactor = getDisplayToBaseFactor(inventory);
          int baseQuantity = (item.getBaseQuantity() != null && item.getBaseQuantity() < 0)
              ? item.getBaseQuantity()
              : toBaseQuantity(item.getQuantity(), factor);
          BigDecimal pricingQuantity = BigDecimal.valueOf(baseQuantity)
              .divide(BigDecimal.valueOf(pricingFactor), 4, RoundingMode.HALF_UP);
          BigDecimal maximumRetailPrice = inventory.getMaximumRetailPrice();

          // For negative quantities, create a PurchaseItem with negative quantity
          // The updateCart method will handle the logic
          PurchaseItem purchaseItem = purchaseMapper.createPurchaseItem(
              item.getId(),
              inventory.getName(),
              pricingQuantity, // Negative quantity in pricing/display units
              maximumRetailPrice,
              BigDecimal.ZERO, // Not used for negative quantities
              BigDecimal.ZERO
          );
          purchaseItem.setSaleUnit(saleUnit);
          purchaseItem.setBaseQuantity(baseQuantity);
          purchaseItem.setUnitFactor(pricingFactor);
          purchaseItem.setAvailableUnits(mapAvailableUnits(inventory));
          applyItemTaxMode(purchaseItem, resolveInventoryBillingMode(inventory));
          purchaseItems.add(purchaseItem);
        } else {
          // Positive quantity - normal flow with stock validation
          Inventory inventory = inventoryRepository.findById(item.getId())
              .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", item.getId()));

          // Verify the inventory belongs to the shop
          if (!shopId.equals(inventory.getShopId())) {
            throw new ValidationException("Inventory lot " + item.getId() + " does not belong to shop " + shopId);
          }
          String saleUnit = normalizeSaleUnit(item.getUnit(), inventory);
          int factor = getConversionFactorToBase(inventory, saleUnit);
          int pricingFactor = getDisplayToBaseFactor(inventory);
          int baseQuantity = resolveRequestedBaseQuantity(item, inventory, saleUnit, factor);
          BigDecimal pricingQuantity = BigDecimal.valueOf(baseQuantity)
              .divide(BigDecimal.valueOf(pricingFactor), 4, RoundingMode.HALF_UP);
          BigDecimal maximumRetailPrice = inventory.getMaximumRetailPrice();

          // Check stock availability
          int availableStock = getCurrentBaseCount(inventory);
          if (availableStock < baseQuantity) {
            throw new InsufficientStockException("Insufficient stock for product: " + inventory.getName(),
                inventory.getBarcode(), availableStock, baseQuantity);
          }

          // Use mapper to create PurchaseItem
          PurchaseItem purchaseItem = purchaseMapper.toPurchaseItemFromCartItem(item, inventory);
          BillingMode itemBillingMode = resolveInventoryBillingMode(inventory);
          applyItemTaxMode(purchaseItem, itemBillingMode);
          normalizeSchemeFields(purchaseItem);
          BigDecimal sellingPrice = inventory.getSellingPrice() != null ? inventory.getSellingPrice() : inventory.getPriceToRetail();
          BigDecimal costPrice = inventory.getCostPrice();
          purchaseItem.setQuantity(pricingQuantity);
          purchaseItem.setMaximumRetailPrice(maximumRetailPrice);
          purchaseItem.setPriceToRetail(sellingPrice);
          purchaseItem.setCostPrice(costPrice);
          purchaseItem.setUnitFactor(pricingFactor);
          BigDecimal perUnitDiscount = maximumRetailPrice
              .subtract(sellingPrice != null ? sellingPrice : BigDecimal.ZERO);
          if (perUnitDiscount.compareTo(BigDecimal.ZERO) > 0) {
            purchaseItem.setDiscount(perUnitDiscount.multiply(getQuantityAsPricingUnits(purchaseItem)));
          } else {
            purchaseItem.setDiscount(BigDecimal.ZERO);
          }
          BigDecimal effectivePrice = getEffectiveSellingPricePerUnit(purchaseItem);
          BigDecimal billableQty = getBillableQuantityAsDecimal(purchaseItem);
          BigDecimal totalAmount = calculateItemTotalAmount(effectivePrice, purchaseItem.getAdditionalDiscount(), billableQty,
              purchaseItem.getCgst(), purchaseItem.getSgst(), isTaxApplicable(itemBillingMode));
          purchaseItem.setTotalAmount(totalAmount);
          purchaseItem.setSaleUnit(saleUnit);
          purchaseItem.setBaseQuantity(baseQuantity);
          purchaseItem.setAvailableUnits(mapAvailableUnits(inventory));
          purchaseMapper.enrichPurchaseItemMargin(purchaseItem);
          purchaseItems.add(purchaseItem);
        }

      } catch (ValidationException | InsufficientStockException | ResourceNotFoundException e) {
        log.warn("Item validation failed for lotId: {} - {}", item.getId(), e.getMessage());
        throw e;
      } catch (Exception e) {
        log.error("Unexpected error processing item with lotId: {}", item.getId(), e);
        throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
            "Error processing item with lotId " + item.getId() + ": " + e.getMessage(), e);
      }
    }

    return purchaseItems;
  }

  private BigDecimal calculateSubtotal(List<PurchaseItem> items) {
    if (items == null || items.isEmpty()) {
      return BigDecimal.ZERO;
    }
    return items.stream()
        .map(item -> {
          BigDecimal effectivePrice = getEffectiveSellingPricePerUnit(item);
          BigDecimal billableQty = getBillableQuantityAsDecimal(item);
          return effectivePrice.multiply(billableQty);
        })
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Calculate tax based on inventory-level SGST and CGST rates from purchase items.
   * Each item's tax is calculated using its own CGST/SGST rates, then summed.
   * If an item doesn't have CGST/SGST, falls back to shop defaults.
   * 
   * @param purchaseItems list of purchase items with inventory-level CGST/SGST
   * @param shopId the shop ID to fetch default tax rates from if item doesn't have rates
   * @return TaxCalculationResult with sgstAmount, cgstAmount, and taxTotal
   */
  private TaxCalculationResult calculateTax(List<PurchaseItem> purchaseItems, String shopId, BillingMode billingMode) {
    if (!isTaxApplicable(billingMode)) {
      return new TaxCalculationResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
    BigDecimal totalSgstAmount = BigDecimal.ZERO;
    BigDecimal totalCgstAmount = BigDecimal.ZERO;
    
    // Get shop defaults for items without inventory-level rates
    String shopSgst = null;
    String shopCgst = null;
    if (shopId != null && !shopId.trim().isEmpty()) {
      Optional<Shop> shopOpt = shopRepository.findById(shopId);
      if (shopOpt.isPresent()) {
        Shop shop = shopOpt.get();
        shopSgst = shop.getSgst();
        shopCgst = shop.getCgst();
      }
    }
    
    // Calculate tax for each item based on its inventory-level CGST/SGST
    for (PurchaseItem item : purchaseItems) {
      // Item total for tax: use paid quantity when scheme is set (billing basis)
      BigDecimal itemTotal = BigDecimal.ZERO;
      if (item.getMaximumRetailPrice() != null && item.getQuantity() != null 
          && item.getPriceToRetail() != null) {
        BigDecimal effectivePrice = getEffectiveSellingPricePerUnit(item);
        BigDecimal billableQty = getBillableQuantityAsDecimal(item);
        itemTotal = effectivePrice.multiply(billableQty);
        // Apply additional discount if present
        if (item.getAdditionalDiscount() != null && item.getAdditionalDiscount().compareTo(BigDecimal.ZERO) > 0) {
          itemTotal = itemTotal.multiply(new BigDecimal(1).subtract(item.getAdditionalDiscount().divide(new BigDecimal(
              "100"), 4, RoundingMode.HALF_UP)));
        }
      }
      
      // Use inventory-level rates if available, otherwise use shop defaults
      String itemSgst = StringUtils.hasText(item.getSgst()) ? item.getSgst() : shopSgst;
      String itemCgst = StringUtils.hasText(item.getCgst()) ? item.getCgst() : shopCgst;
      
      // Calculate SGST for this item
      if (itemSgst != null && !itemSgst.trim().isEmpty()) {
        try {
          BigDecimal sgstRate = new BigDecimal(itemSgst.trim()).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
          BigDecimal itemSgstAmount = itemTotal.multiply(sgstRate).setScale(2, RoundingMode.HALF_UP);
          totalSgstAmount = totalSgstAmount.add(itemSgstAmount);
        } catch (NumberFormatException e) {
          log.warn("Invalid SGST value '{}' for item {}, using 0", itemSgst, item.getInventoryId());
        }
      }
      
      // Calculate CGST for this item
      if (itemCgst != null && !itemCgst.trim().isEmpty()) {
        try {
          BigDecimal cgstRate = new BigDecimal(itemCgst.trim()).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
          BigDecimal itemCgstAmount = itemTotal.multiply(cgstRate).setScale(2, RoundingMode.HALF_UP);
          totalCgstAmount = totalCgstAmount.add(itemCgstAmount);
        } catch (NumberFormatException e) {
          log.warn("Invalid CGST value '{}' for item {}, using 0", itemCgst, item.getInventoryId());
        }
      }
    }
    
    BigDecimal taxTotal = totalSgstAmount.add(totalCgstAmount);
    return new TaxCalculationResult(totalSgstAmount, totalCgstAmount, taxTotal);
  }
  
  /**
   * Inner class to hold tax calculation results.
   */
  private static class TaxCalculationResult {
    private final BigDecimal sgstAmount;
    private final BigDecimal cgstAmount;
    private final BigDecimal taxTotal;
    
    public TaxCalculationResult(BigDecimal sgstAmount, BigDecimal cgstAmount, BigDecimal taxTotal) {
      this.sgstAmount = sgstAmount;
      this.cgstAmount = cgstAmount;
      this.taxTotal = taxTotal;
    }
    
    public BigDecimal getSgstAmount() {
      return sgstAmount;
    }
    
    public BigDecimal getCgstAmount() {
      return cgstAmount;
    }
    
    public BigDecimal getTaxTotal() {
      return taxTotal;
    }
  }

  private BigDecimal calculateTotalDiscount(List<PurchaseItem> items) {
    if (items == null || items.isEmpty()) {
      return BigDecimal.ZERO;
    }
    return items.stream()
        .map(item -> {
          BigDecimal mrp = item.getMaximumRetailPrice() != null ? item.getMaximumRetailPrice() : BigDecimal.ZERO;
          BigDecimal effectivePrice = getEffectiveSellingPricePerUnit(item);
          BigDecimal billableQty = getBillableQuantityAsDecimal(item);
          return mrp.subtract(effectivePrice).multiply(billableQty);
        })
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Calculate totalAmount for a single purchase item.
   * Formula:
   * 1. Apply additionalDiscount to priceToRetail: priceToRetail * (1 - additionalDiscount/100)
   * 2. Multiply by quantity
   * 3. Add CGST and SGST: totalDiscountedAmount * (1 + cgst/100 + sgst/100)
   */
  private BigDecimal calculateItemTotalAmount(BigDecimal priceToRetail, BigDecimal additionalDiscount,
                                               BigDecimal billableQuantity, String cgst, String sgst,
                                               boolean includeTax) {
    if (priceToRetail == null || billableQuantity == null || billableQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    // Step 1: Calculate discounted selling price per unit
    BigDecimal discountedPricePerUnit = priceToRetail;
    if (additionalDiscount != null && additionalDiscount.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal discountMultiplier = BigDecimal.ONE.subtract(
          additionalDiscount.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
      );
      discountedPricePerUnit = priceToRetail.multiply(discountMultiplier);
    }
    // Step 2: Multiply by billable quantity (can be fractional for FIXED_UNITS scheme)
    BigDecimal totalDiscountedAmount = discountedPricePerUnit.multiply(billableQuantity);
    
    // Step 3: Add CGST and SGST when billing mode requires tax.
    if (!includeTax) {
      return totalDiscountedAmount.setScale(2, RoundingMode.HALF_UP);
    }
    BigDecimal taxMultiplier = BigDecimal.ONE;
    if (cgst != null && StringUtils.hasText(cgst)) {
      try {
        BigDecimal cgstRate = new BigDecimal(cgst.trim()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        taxMultiplier = taxMultiplier.add(cgstRate);
      } catch (NumberFormatException e) {
        // Invalid CGST rate, ignore
      }
    }
    if (sgst != null && StringUtils.hasText(sgst)) {
      try {
        BigDecimal sgstRate = new BigDecimal(sgst.trim()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        taxMultiplier = taxMultiplier.add(sgstRate);
      } catch (NumberFormatException e) {
        // Invalid SGST rate, ignore
      }
    }
    
    BigDecimal totalAmount = totalDiscountedAmount.multiply(taxMultiplier);
    return totalAmount.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Calculate total additional discount amount.
   * Additional discount is a percentage applied on selling price.
   * Formula: (priceToRetail * quantity) * (additionalDiscount / 100)
   */
  private BigDecimal calculateAdditionalDiscountTotal(List<PurchaseItem> items) {
    if (items == null || items.isEmpty()) {
      return BigDecimal.ZERO;
    }
    return items.stream()
        .map(item -> {
          BigDecimal effectivePrice = getEffectiveSellingPricePerUnit(item);
          BigDecimal additionalDiscount = item.getAdditionalDiscount() != null ? item.getAdditionalDiscount() : BigDecimal.ZERO;
          BigDecimal billableQty = getBillableQuantityAsDecimal(item);
          BigDecimal itemTotal = effectivePrice.multiply(billableQty);
          BigDecimal discountAmount = itemTotal.multiply(additionalDiscount.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
          return discountAmount;
        })
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }

  private void recalculateLineTotalsForBillingMode(List<PurchaseItem> items, BillingMode billingMode) {
    if (items == null || items.isEmpty()) {
      return;
    }
    boolean includeTax = isTaxApplicable(billingMode);
    for (PurchaseItem item : items) {
      applyItemTaxMode(item, billingMode);
      BigDecimal effectivePrice = getEffectiveSellingPricePerUnit(item);
      BigDecimal billableQty = getBillableQuantityAsDecimal(item);
      item.setTotalAmount(calculateItemTotalAmount(
          effectivePrice,
          item.getAdditionalDiscount(),
          billableQty,
          item.getCgst(),
          item.getSgst(),
          includeTax));
      purchaseMapper.enrichPurchaseItemMargin(item);
    }
  }

  /**
   * Set purchase-level margin breakdown: totalCost, revenueBeforeTax, totalProfit, marginPercent.
   * revenueBeforeTax = subTotal − additionalDiscountTotal; totalProfit = revenueBeforeTax − totalCost.
   */
  private void setPurchaseMarginDetails(Purchase purchase) {
    if (purchase == null || purchase.getItems() == null || purchase.getItems().isEmpty()) {
      return;
    }
    BigDecimal totalCost = purchase.getItems().stream()
        .map(item -> item.getCostTotal() != null ? item.getCostTotal() : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
    purchase.setTotalCost(totalCost);
    BigDecimal subTotal = purchase.getSubTotal() != null ? purchase.getSubTotal() : BigDecimal.ZERO;
    BigDecimal additionalDiscountTotal = purchase.getAdditionalDiscountTotal() != null ? purchase.getAdditionalDiscountTotal() : BigDecimal.ZERO;
    BigDecimal revenueBeforeTax = subTotal.subtract(additionalDiscountTotal).setScale(2, RoundingMode.HALF_UP);
    purchase.setRevenueBeforeTax(revenueBeforeTax);
    BigDecimal revenueAfterTax = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO;
    purchase.setRevenueAfterTax(revenueAfterTax.setScale(2, RoundingMode.HALF_UP));
    BigDecimal totalProfit = revenueBeforeTax.subtract(totalCost).setScale(2, RoundingMode.HALF_UP);
    purchase.setTotalProfit(totalProfit);
    if (revenueBeforeTax.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal marginPercent = totalProfit.multiply(BigDecimal.valueOf(100))
          .divide(revenueBeforeTax, 2, RoundingMode.HALF_UP);
      purchase.setMarginPercent(marginPercent);
    } else {
      purchase.setMarginPercent(null);
    }
  }

  private Purchase createCart(AddToCartRequest request, List<PurchaseItem> purchaseItems, String shopId, String userId,
                              String customerId, String customerName, BillingMode billingMode) {
    try {
      recalculateLineTotalsForBillingMode(purchaseItems, billingMode);
      // Calculate totals
      BigDecimal subTotal = calculateSubtotal(purchaseItems);
      TaxCalculationResult taxResult = calculateTax(purchaseItems, shopId, billingMode);
      BigDecimal discountTotal = calculateTotalDiscount(purchaseItems);
      BigDecimal additionalDiscountTotal = calculateAdditionalDiscountTotal(purchaseItems);
      BigDecimal grandTotal = subTotal.add(taxResult.getTaxTotal()).subtract(additionalDiscountTotal);

      // Create purchase with CREATED status using mapper
      // MongoDB will auto-generate the id as ObjectId
      Purchase purchase = purchaseMapper.toPurchaseForCart(
          request, purchaseItems, subTotal, taxResult.getTaxTotal(), discountTotal, grandTotal, shopId, userId, customerId, billingMode
      );
      if (billingMode == BillingMode.BASIC) {
        purchase.setInvoiceNo(invoiceSequenceService.getNextBasicInvoiceNo(shopId));
      } else {
        purchase.setInvoiceNo(invoiceSequenceService.getNextInvoiceNo(shopId));
      }
      // Set tax amounts, additional discount, and customerName
      purchase.setSgstAmount(taxResult.getSgstAmount());
      purchase.setCgstAmount(taxResult.getCgstAmount());
      purchase.setAdditionalDiscountTotal(additionalDiscountTotal);
      setPurchaseMarginDetails(purchase);

      if (StringUtils.hasText(customerName)) {
        purchase.setCustomerName(customerName);
      }

      return purchaseRepository.save(purchase);
    } catch (DataAccessException e) {
      log.error("Database error while creating cart for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error creating cart: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error while creating cart for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error creating cart: " + e.getMessage(), e);
    }
  }

  /**
   * Get or create customer ID from request.
   * - If only customer name is provided, return null (no customer record created)
   * - If customer phone is present, search for existing customer or create new one
   */
  private String getOrCreateCustomerId(String shopId, AddToCartRequest request) {
    // If customer phone is present, search for existing customer or create new one
    if (StringUtils.hasText(request.getCustomerPhone())) {
      // Search for existing customer by phone
      java.util.Optional<com.inventory.user.domain.model.Customer> existingCustomerOpt =
          customerService.searchCustomerByPhone(request.getCustomerPhone().trim(), shopId);

      if (existingCustomerOpt.isPresent()) {
        // Customer found, use its ID
        log.debug("Found existing customer with ID: {} for phone: {}", 
            existingCustomerOpt.get().getId(), request.getCustomerPhone());
        return existingCustomerOpt.get().getId();
      } else {
        // Customer not found, create new customer with given details
        com.inventory.user.domain.model.Customer customer = customerService.findOrCreateCustomer(
            shopId,
            request.getCustomerName(),
            request.getCustomerPhone(),
            request.getCustomerAddress(),
            request.getCustomerEmail(),
            request.getCustomerGstin(),
            request.getCustomerDlNo(),
            request.getCustomerPan()
        );
        if (customer != null) {
          log.debug("Created new customer with ID: {} for phone: {}", 
              customer.getId(), request.getCustomerPhone());
          return customer.getId();
        }
      }
    } else if (StringUtils.hasText(request.getCustomerName())) {
      // Only customer name provided, no phone - don't create customer record
      // Just return null (customerId will be null in purchase)
      log.debug("Only customer name provided, not creating customer record");
      return null;
    }

    // No customer info provided
    return null;
  }

  private Purchase updateCart(Purchase existingCart, List<PurchaseItem> newItems, String businessType,
                              String customerId, String customerName, BillingMode billingMode) {
    try {
      // Merge items - if same inventoryId exists, update quantity; otherwise add new
      List<PurchaseItem> mergedItems = new ArrayList<>(existingCart.getItems() != null ? existingCart.getItems() : new ArrayList<>());
      boolean includeTax = isTaxApplicable(billingMode);

      for (PurchaseItem newItem : newItems) {
        boolean found = false;
        for (int i = 0; i < mergedItems.size(); i++) {
          PurchaseItem existingItem = mergedItems.get(i);
          if (existingItem.getInventoryId().equals(newItem.getInventoryId())) {
            String existingSaleUnit = existingItem.getSaleUnit() != null ? existingItem.getSaleUnit() : "UNIT";
            String incomingSaleUnit = newItem.getSaleUnit() != null ? newItem.getSaleUnit() : existingSaleUnit;
            if (!existingSaleUnit.equals(incomingSaleUnit)) {
              Inventory inventory = inventoryRepository.findById(existingItem.getInventoryId())
                  .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", existingItem.getInventoryId()));
              int existingBaseQuantity = getBaseQuantityOrFallback(existingItem);
              int incomingBaseQuantity = getBaseQuantityOrFallback(newItem);
              int switchBaseQuantity = incomingBaseQuantity > 0 ? incomingBaseQuantity : existingBaseQuantity;
              int targetFactor = getConversionFactorToBase(inventory, incomingSaleUnit);
              if (targetFactor > 1) {
                int roundedWholeUnits = switchBaseQuantity / targetFactor;
                if (roundedWholeUnits <= 0) {
                  throw new ValidationException("Cannot switch from " + existingSaleUnit + " to " + incomingSaleUnit
                      + " because quantity is less than one whole " + incomingSaleUnit);
                }
                switchBaseQuantity = roundedWholeUnits * targetFactor;
              }
              int pricingFactor = getDisplayToBaseFactor(inventory);
              BigDecimal quantityForPricing = BigDecimal.valueOf(switchBaseQuantity)
                  .divide(BigDecimal.valueOf(pricingFactor), 4, RoundingMode.HALF_UP);
              BigDecimal priceToRetail = newItem.getPriceToRetail() != null ? newItem.getPriceToRetail() : existingItem.getPriceToRetail();
              BigDecimal maximumRetailPrice = inventory.getMaximumRetailPrice();
              BigDecimal costPrice = inventory.getCostPrice();
              BigDecimal perUnitDiscount = maximumRetailPrice.subtract(priceToRetail != null ? priceToRetail : BigDecimal.ZERO);

              PurchaseItem switchedItem = purchaseMapper.createPurchaseItem(
                  existingItem.getInventoryId(),
                  existingItem.getName(),
                  quantityForPricing,
                  maximumRetailPrice,
                  priceToRetail,
                  perUnitDiscount.compareTo(BigDecimal.ZERO) > 0
                      ? perUnitDiscount.multiply(quantityForPricing)
                      : BigDecimal.ZERO
              );
              switchedItem.setAdditionalDiscount(newItem.getAdditionalDiscount() != null
                  ? newItem.getAdditionalDiscount()
                  : existingItem.getAdditionalDiscount());
              SchemeType switchedSchemeType = newItem.getSchemeType() != null ? newItem.getSchemeType() : existingItem.getSchemeType();
              switchedItem.setSchemeType(switchedSchemeType);
              switchedItem.setSchemePercentage(newItem.getSchemePercentage() != null
                  ? newItem.getSchemePercentage()
                  : existingItem.getSchemePercentage());
              if (switchedSchemeType == SchemeType.PERCENTAGE) {
                switchedItem.setSchemePayFor(null);
                switchedItem.setSchemeFree(null);
              } else {
                switchedItem.setSchemePayFor(newItem.getSchemePayFor() != null ? newItem.getSchemePayFor() : existingItem.getSchemePayFor());
                switchedItem.setSchemeFree(newItem.getSchemeFree() != null ? newItem.getSchemeFree() : existingItem.getSchemeFree());
                switchedItem.setSchemePercentage(null);
              }
              switchedItem.setSgst(existingItem.getSgst());
              switchedItem.setCgst(existingItem.getCgst());
              switchedItem.setCostPrice(costPrice);
              switchedItem.setSaleUnit(incomingSaleUnit);
              switchedItem.setBaseQuantity(switchBaseQuantity);
              switchedItem.setUnitFactor(pricingFactor);
              switchedItem.setAvailableUnits(mapAvailableUnits(inventory));
              applyItemTaxMode(switchedItem, billingMode);
              BigDecimal effectivePrice = getEffectiveSellingPricePerUnit(switchedItem);
              BigDecimal billableQty = getBillableQuantityAsDecimal(switchedItem);
              BigDecimal totalAmount = calculateItemTotalAmount(
                  effectivePrice,
                  switchedItem.getAdditionalDiscount(),
                  billableQty,
                  switchedItem.getCgst(),
                  switchedItem.getSgst(),
                  includeTax
              );
              switchedItem.setTotalAmount(totalAmount);
              purchaseMapper.enrichPurchaseItemMargin(switchedItem);

              mergedItems.set(i, switchedItem);
              found = true;
              break;
            }
            // Handle quantity update based on positive or negative
            int existingBaseQuantity = getBaseQuantityOrFallback(existingItem);
            int incomingBaseQuantity = getBaseQuantityOrFallback(newItem);
            int newBaseQuantity = existingBaseQuantity + incomingBaseQuantity;

            // Case 3: If negative value is more negative or equal to current item quantity, remove the item
            if (newBaseQuantity <= 0) {
              mergedItems.remove(i);
              found = true;
              break;
            }

            // Case 1 & 2: Update quantity (positive adds, negative decreases). Use payload priceToRetail when provided (update-only or add); for negative qty we only remove, keep existing price.
            Inventory inventory = inventoryRepository.findById(existingItem.getInventoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", existingItem.getInventoryId()));
            BigDecimal priceToRetail = ((newItem.getQuantity() == null || newItem.getQuantity().compareTo(BigDecimal.ZERO) >= 0)
                && newItem.getPriceToRetail() != null)
                ? newItem.getPriceToRetail()
                : existingItem.getPriceToRetail();
            int pricingFactor = existingItem.getUnitFactor() != null ? existingItem.getUnitFactor() : getDefaultFactor(inventory);
            BigDecimal maximumRetailPrice = inventory.getMaximumRetailPrice();
            BigDecimal costPrice = inventory.getCostPrice();
            BigDecimal newQuantity = BigDecimal.valueOf(newBaseQuantity)
                .divide(BigDecimal.valueOf(pricingFactor), 4, RoundingMode.HALF_UP);
            BigDecimal newDiscount = existingItem.getMaximumRetailPrice()
                .subtract(priceToRetail)
                .multiply(newQuantity);
            // Use payload additionalDiscount when provided (so changing discount updates the line); otherwise keep existing
            BigDecimal additionalDiscount = newItem.getAdditionalDiscount() != null
                ? newItem.getAdditionalDiscount()
                : existingItem.getAdditionalDiscount();
            // Use payload scheme when provided; otherwise keep existing
            Integer schemePayFor = newItem.getSchemePayFor() != null ? newItem.getSchemePayFor() : existingItem.getSchemePayFor();
            Integer schemeFree = newItem.getSchemeFree() != null ? newItem.getSchemeFree() : existingItem.getSchemeFree();
            SchemeType schemeType = newItem.getSchemeType() != null ? newItem.getSchemeType() : existingItem.getSchemeType();
            BigDecimal schemePercentage = newItem.getSchemePercentage() != null ? newItem.getSchemePercentage() : existingItem.getSchemePercentage();

            PurchaseItem updatedItem = purchaseMapper.createPurchaseItem(
                existingItem.getInventoryId(),
                existingItem.getName(),
                newQuantity,
                maximumRetailPrice,
                priceToRetail,
                newDiscount.compareTo(BigDecimal.ZERO) > 0 ? newDiscount : BigDecimal.ZERO
            );
            updatedItem.setAdditionalDiscount(additionalDiscount);
            updatedItem.setSchemeType(schemeType);
            updatedItem.setSchemePercentage(schemePercentage);
            // Normalize: PERCENTAGE -> payFor/free null; FIXED_UNITS -> schemePercentage null
            if (schemeType == SchemeType.PERCENTAGE) {
              updatedItem.setSchemePayFor(null);
              updatedItem.setSchemeFree(null);
            } else {
              updatedItem.setSchemePayFor(schemePayFor);
              updatedItem.setSchemeFree(schemeFree);
              updatedItem.setSchemePercentage(null);
            }
            updatedItem.setSgst(existingItem.getSgst());
            updatedItem.setCgst(existingItem.getCgst());
            updatedItem.setCostPrice(costPrice);
            updatedItem.setSaleUnit(existingSaleUnit);
            updatedItem.setBaseQuantity(newBaseQuantity);
            updatedItem.setUnitFactor(pricingFactor);
            updatedItem.setAvailableUnits(mapAvailableUnits(inventory));
            applyItemTaxMode(updatedItem, billingMode);
            BigDecimal perUnitDiscount = maximumRetailPrice.subtract(
                priceToRetail != null ? priceToRetail : BigDecimal.ZERO);
            if (perUnitDiscount.compareTo(BigDecimal.ZERO) > 0) {
              updatedItem.setDiscount(perUnitDiscount.multiply(getQuantityAsPricingUnits(updatedItem)));
            } else {
              updatedItem.setDiscount(BigDecimal.ZERO);
            }
            BigDecimal effectivePrice = getEffectiveSellingPricePerUnit(updatedItem);
            BigDecimal billableQty = getBillableQuantityAsDecimal(updatedItem);
            BigDecimal totalAmount = calculateItemTotalAmount(effectivePrice, additionalDiscount, billableQty,
                updatedItem.getCgst(), updatedItem.getSgst(), includeTax);
            updatedItem.setTotalAmount(totalAmount);
            purchaseMapper.enrichPurchaseItemMargin(updatedItem);
            mergedItems.set(i, updatedItem);
            found = true;
            break;
          }
        }
        // Case 1: If item not found and quantity is positive, add new item
        if (!found && getBaseQuantityOrFallback(newItem) > 0) {
          applyItemTaxMode(newItem, billingMode);
          mergedItems.add(newItem);
        }
        // Case 2 & 3: If item not found and quantity is negative, throw error (can't remove what doesn't exist)
        if (!found && getBaseQuantityOrFallback(newItem) < 0) {
          throw new ValidationException("Cannot remove item with lotId " + newItem.getInventoryId() +
              " as it does not exist in the cart");
        }
      }

      // Update business type if provided
      if (StringUtils.hasText(businessType)) {
        existingCart.setBusinessType(businessType);
      }
      existingCart.setBillingMode(billingMode);

      // Update customer ID and customerName
      existingCart.setCustomerId(customerId);
      if (StringUtils.hasText(customerName)) {
        existingCart.setCustomerName(customerName);
      } else if (customerId == null) {
        // If customerId is null and no customerName provided, clear customerName
        existingCart.setCustomerName(null);
      }

      // Update updatedAt timestamp
      existingCart.setUpdatedAt(Instant.now());

      // Recalculate totals
      recalculateLineTotalsForBillingMode(mergedItems, billingMode);
      existingCart.setItems(mergedItems);
      BigDecimal newSubTotal = calculateSubtotal(mergedItems);
      existingCart.setSubTotal(newSubTotal);
      
      TaxCalculationResult taxResult = calculateTax(mergedItems, existingCart.getShopId(), billingMode);
      existingCart.setTaxTotal(taxResult.getTaxTotal());
      existingCart.setSgstAmount(taxResult.getSgstAmount());
      existingCart.setCgstAmount(taxResult.getCgstAmount());
      
      BigDecimal discountTotal = calculateTotalDiscount(mergedItems);
      BigDecimal additionalDiscountTotal = calculateAdditionalDiscountTotal(mergedItems);
      existingCart.setDiscountTotal(discountTotal);
      existingCart.setAdditionalDiscountTotal(additionalDiscountTotal);
      existingCart.setGrandTotal(newSubTotal
          .add(taxResult.getTaxTotal())
          .subtract(additionalDiscountTotal));
      setPurchaseMarginDetails(existingCart);

      // If cart is empty after updates, we can either delete it or keep it with empty items
      // For now, we'll keep it with empty items (status remains CREATED)
      // You can add logic here to delete the cart if needed

      return purchaseRepository.save(existingCart);
    } catch (DataAccessException e) {
      log.error("Database error while updating cart: {}", existingCart.getId(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error updating cart: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error while updating cart: {}", existingCart.getId(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error updating cart: " + e.getMessage(), e);
    }
  }

  private void validateStockAvailabilityForCartUpdate(Purchase existingCart, List<PurchaseItem> newItems, String shopId) {
    // Create a map of existing cart items by inventoryId for quick lookup
    Map<String, PurchaseItem> existingItemsMap = new HashMap<>();
    if (existingCart.getItems() != null) {
      for (PurchaseItem item : existingCart.getItems()) {
        existingItemsMap.put(item.getInventoryId(), item);
      }
    }

    // Validate each new item
    for (PurchaseItem newItem : newItems) {
      // Only validate stock for positive quantities (adding items)
      if (getBaseQuantityOrFallback(newItem) > 0) {
        PurchaseItem existingItem = existingItemsMap.get(newItem.getInventoryId());
        int currentCartBaseQuantity = existingItem != null ? getBaseQuantityOrFallback(existingItem) : 0;
        int addingBaseQuantity = getBaseQuantityOrFallback(newItem);
        // Get inventory to check available stock
        Inventory inventory = inventoryRepository.findById(newItem.getInventoryId())
            .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", newItem.getInventoryId()));

        // Verify inventory belongs to shop
        if (!shopId.equals(inventory.getShopId())) {
          throw new ValidationException("Inventory lot " + newItem.getInventoryId() + " does not belong to shop " + shopId);
        }

        int finalBaseQuantity = currentCartBaseQuantity + addingBaseQuantity;
        if (existingItem != null) {
          String existingSaleUnit = existingItem.getSaleUnit() != null ? existingItem.getSaleUnit() : "UNIT";
          String incomingSaleUnit = newItem.getSaleUnit() != null ? newItem.getSaleUnit() : existingSaleUnit;
          if (!existingSaleUnit.equals(incomingSaleUnit)) {
            int targetFactor = getConversionFactorToBase(inventory, incomingSaleUnit);
            int requestedBase = addingBaseQuantity > 0 ? addingBaseQuantity : currentCartBaseQuantity;
            if (targetFactor > 1) {
              requestedBase = (requestedBase / targetFactor) * targetFactor;
            }
            finalBaseQuantity = requestedBase;
          }
        }

        // Check if final quantity exceeds available stock
        int availableStock = getCurrentBaseCount(inventory);
        if (finalBaseQuantity > availableStock) {
          throw new InsufficientStockException(
              "Insufficient stock for product: " + inventory.getName() +
                  ". Available: " + availableStock +
                  ", Requested final quantity in base units: " + finalBaseQuantity +
                  " (current in cart base units: " + currentCartBaseQuantity + ", adding base units: " + addingBaseQuantity + ")",
              inventory.getBarcode(), availableStock, finalBaseQuantity);
        }
      }
    }
  }

  private String normalizeSaleUnit(String requestedUnit, Inventory inventory) {
    if (StringUtils.hasText(requestedUnit)) {
      return requestedUnit.trim().toUpperCase();
    }
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion != null && StringUtils.hasText(conversion.getUnit())) {
      return conversion.getUnit().trim().toUpperCase();
    }
    if (StringUtils.hasText(inventory.getBaseUnit())) {
      return inventory.getBaseUnit().trim().toUpperCase();
    }
    return "UNIT";
  }

  private int resolveSaleQuantity(Integer requestedQuantity, String requestedUnit, Inventory inventory, String saleUnit) {
    return requestedQuantity != null ? requestedQuantity : 0;
  }

  private int resolveRequestedBaseQuantity(AddToCartRequest.CartItem item, Inventory inventory, String saleUnit, int defaultFactor) {
    if (item.getBaseQuantity() != null && item.getBaseQuantity() != 0) {
      return item.getBaseQuantity();
    }
    if (item.getQuantity() == null) {
      return 0;
    }
    if (!StringUtils.hasText(item.getUnit())) {
      return toBaseQuantity(item.getQuantity(), defaultFactor);
    }
    int factor = getConversionFactorToBase(inventory, saleUnit);
    return toBaseQuantity(item.getQuantity(), factor);
  }

  private int getDefaultFactor(Inventory inventory) {
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion == null) {
      return 1;
    }
    if (conversion.getFactor() <= 0) {
      throw new ValidationException("Invalid unit conversion factor configured for product " + inventory.getName());
    }
    return conversion.getFactor();
  }

  private BigDecimal resolveMaximumRetailPriceForSaleUnit(Inventory inventory, String saleUnit) {
    BigDecimal maximumRetailPrice = inventory.getMaximumRetailPrice() != null
        ? inventory.getMaximumRetailPrice()
        : BigDecimal.ZERO;
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion == null) {
      return maximumRetailPrice;
    }
    String conversionUnit = conversion.getUnit().trim().toUpperCase();
    int factor = conversion.getFactor();
    if (factor <= 0) {
      throw new ValidationException("Invalid unit conversion factor configured for product " + inventory.getName());
    }
    String baseUnit = StringUtils.hasText(inventory.getBaseUnit())
        ? inventory.getBaseUnit().trim().toUpperCase()
        : "UNIT";
    if (saleUnit.equals(conversionUnit)) {
      return maximumRetailPrice;
    }
    if (saleUnit.equals(baseUnit)) {
      return maximumRetailPrice.divide(BigDecimal.valueOf(factor), 2, RoundingMode.HALF_UP);
    }
    throw new ValidationException("Unit " + saleUnit + " is not configured for product " + inventory.getName());
  }

  private BigDecimal normalizePriceForSaleUnit(BigDecimal price, Inventory inventory, String saleUnit) {
    if (price == null) {
      return null;
    }
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion == null) {
      return price;
    }
    int factor = conversion.getFactor();
    if (factor <= 0) {
      throw new ValidationException("Invalid unit conversion factor configured for product " + inventory.getName());
    }
    String conversionUnit = conversion.getUnit().trim().toUpperCase();
    String baseUnit = StringUtils.hasText(inventory.getBaseUnit())
        ? inventory.getBaseUnit().trim().toUpperCase()
        : "UNIT";
    if (saleUnit.equals(conversionUnit)) {
      return price;
    }
    if (saleUnit.equals(baseUnit)) {
      return price.divide(BigDecimal.valueOf(factor), 2, RoundingMode.HALF_UP);
    }
    throw new ValidationException("Unit " + saleUnit + " is not configured for product " + inventory.getName());
  }

  private UnitConversion getConfiguredUnitConversion(Inventory inventory) {
    UnitConversion conversion = inventory.getUnitConversions();
    if (conversion == null) {
      return null;
    }
    if (!StringUtils.hasText(conversion.getUnit()) || conversion.getFactor() == null) {
      return null;
    }
    return conversion;
  }

  private int getConversionFactorToBase(Inventory inventory, String saleUnit) {
    String baseUnit = StringUtils.hasText(inventory.getBaseUnit())
        ? inventory.getBaseUnit().trim().toUpperCase()
        : "UNIT";
    if (baseUnit.equals(saleUnit)) {
      return 1;
    }
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion != null
        && saleUnit.equals(conversion.getUnit().trim().toUpperCase())) {
      if (conversion.getFactor() <= 0) {
        throw new ValidationException("Invalid unit conversion factor configured for unit: " + saleUnit);
      }
      return conversion.getFactor();
    }
    throw new ValidationException("Unit " + saleUnit + " is not configured for product " + inventory.getName());
  }

  private int toBaseQuantity(Integer quantity, int factor) {
    try {
      return Math.multiplyExact(quantity, factor);
    } catch (ArithmeticException e) {
      throw new ValidationException("Quantity is too large after unit conversion");
    }
  }

  private int getBaseQuantityOrFallback(PurchaseItem item) {
    if (item.getBaseQuantity() != null) {
      return item.getBaseQuantity();
    }
    return item.getQuantity() != null ? item.getQuantity().setScale(0, RoundingMode.HALF_UP).intValue() : 0;
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
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion == null || conversion.getFactor() == null || conversion.getFactor() <= 0) {
      return 1;
    }
    return conversion.getFactor();
  }

  private List<AvailableUnit> mapAvailableUnits(Inventory inventory) {
    List<AvailableUnit> units = new ArrayList<>();
    if (inventory == null) {
      return units;
    }
    if (StringUtils.hasText(inventory.getBaseUnit())) {
      units.add(new AvailableUnit(inventory.getBaseUnit().trim().toUpperCase(), true));
    }
    UnitConversion conversion = getConfiguredUnitConversion(inventory);
    if (conversion != null && StringUtils.hasText(conversion.getUnit())) {
      String conversionUnit = conversion.getUnit().trim().toUpperCase();
      boolean alreadyPresent = units.stream().anyMatch(u -> conversionUnit.equals(u.getUnit()));
      if (!alreadyPresent) {
        units.add(new AvailableUnit(conversionUnit, false));
      }
    }
    return units;
  }

  private void updateInventoryForCompletedPurchase(Purchase purchase) {
    if (purchase.getItems() == null || purchase.getItems().isEmpty()) {
      log.warn("Purchase {} has no items to process for inventory update", purchase.getId());
      return;
    }

    log.info("Updating inventory for {} items in purchase {}", purchase.getItems().size(), purchase.getId());

    for (PurchaseItem item : purchase.getItems()) {
      try {
        // Find inventory by lotId (inventoryId in PurchaseItem)
        Inventory inventory = inventoryRepository.findById(item.getInventoryId())
            .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId",
                "Inventory not found with lotId: " + item.getInventoryId()));

        // Verify inventory belongs to the same shop
        if (!purchase.getShopId().equals(inventory.getShopId())) {
          throw new ValidationException("Inventory lot " + item.getInventoryId() +
              " does not belong to shop " + purchase.getShopId());
        }

        // Get current values (handle nulls)
        int currentCount = getCurrentBaseCount(inventory);
        int soldCount = getSoldBaseCount(inventory);
        BigDecimal currentDisplayCount = getCurrentDisplayCount(inventory);
        BigDecimal soldDisplayCount = getSoldDisplayCount(inventory);
        int baseQuantity = getBaseQuantityOrFallback(item);
        BigDecimal displayQuantity = toDisplayQuantity(baseQuantity, inventory);

        // Validate that we have enough stock
        if (currentCount < baseQuantity) {
          throw new InsufficientStockException(
              "Insufficient stock to complete purchase for product: " + inventory.getName() +
                  ". Available: " + currentCount + ", Required in base units: " + baseQuantity,
              inventory.getBarcode(), currentCount, baseQuantity);
        }

        // Update inventory counts
        inventory.setCurrentCount(currentDisplayCount.subtract(displayQuantity).setScale(4, RoundingMode.HALF_UP));
        inventory.setSoldCount(soldDisplayCount.add(displayQuantity).setScale(4, RoundingMode.HALF_UP));
        inventory.setCurrentBaseCount(currentCount - baseQuantity);
        inventory.setSoldBaseCount(soldCount + baseQuantity);

        // Save updated inventory
        inventoryRepository.save(inventory);
        Integer threshold = inventory.getThresholdCount() != null
          ? inventory.getThresholdCount()
          : 50;

        log.info(
          "Threshold check -> lotId={}, current={}, threshold={}",
          inventory.getId(),
          inventory.getCurrentBaseCount(),
          threshold
        );

        if (inventory.getCurrentBaseCount() <= threshold) {

          log.info("THRESHOLD REACHED — triggering INVENTORY_LOW event");

          InventoryEventDto dto =
            inventoryMapper.toInventoryLowEventDto(inventory, threshold);
          var eventDto = inventoryMapper.toNotificationEventDto(dto);
          eventService.recordAndBroadcastInventoryLow(eventDto);
        }

        log.info("Updated inventory for lotId: {} - decreased currentCount by {} (new: {}), increased soldCount by {} (new: {})",
            item.getInventoryId(), baseQuantity, inventory.getCurrentBaseCount(), baseQuantity, inventory.getSoldBaseCount());

      } catch (ResourceNotFoundException | ValidationException | InsufficientStockException e) {
        log.error("Error updating inventory for lotId: {} - {}", item.getInventoryId(), e.getMessage());
        throw e;
      } catch (Exception e) {
        log.error("Unexpected error updating inventory for lotId: {}", item.getInventoryId(), e);
        throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
            "Error updating inventory for lotId " + item.getInventoryId() + ": " + e.getMessage(), e);
      }
    }

    log.info("Successfully updated inventory for all items in purchase {}", purchase.getId());
  }

}
