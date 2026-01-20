package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.InsufficientStockException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.PurchaseStatus;
import com.inventory.product.domain.model.Shop;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@Transactional(readOnly = true)
public class CheckoutService {

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

      Purchase purchase;
      if (existingCart != null) {
        // Validate stock availability before updating cart (check final quantities)
        validateStockAvailabilityForCartUpdate(existingCart, newItems, shopId);

        // Update existing cart - merge items
        log.info("Updating existing cart with ID: {}", existingCart.getId());
        purchase = updateCart(existingCart, newItems, request.getBusinessType(), customerId, customerName);
      } else {
        // Create new cart (stock validation already done in processCartItems for positive quantities)
        log.info("Creating new cart");
        purchase = createCart(request, newItems, shopId, userId, customerId, customerName);
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

      // If status is being changed to COMPLETED, decrease inventory counts
      if (requestedStatus == PurchaseStatus.COMPLETED) {
        log.info("Processing inventory updates for completed purchase ID: {}", purchase.getId());
        updateInventoryForCompletedPurchase(purchase);
      }

      // Update status and payment method
      purchase.setStatus(requestedStatus);
      purchase.setPaymentMethod(request.getPaymentMethod());
      purchase.setUpdatedAt(Instant.now());
      purchase = purchaseRepository.save(purchase);

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

        // For negative quantities, we only need to verify the lotId exists and belongs to the shop
        // Stock validation is not needed for removing items
        if (item.getQuantity() < 0) {
          // Verify lotId exists and belongs to shop (for negative quantities, we're removing from cart)
          Inventory inventory = inventoryRepository.findById(item.getId())
              .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", item.getId()));

          if (!shopId.equals(inventory.getShopId())) {
            throw new ValidationException("Inventory lot " + item.getId() + " does not belong to shop " + shopId);
          }

          // For negative quantities, create a PurchaseItem with negative quantity
          // The updateCart method will handle the logic
          PurchaseItem purchaseItem = purchaseMapper.createPurchaseItem(
              item.getId(),
              inventory.getName(),
              item.getQuantity(), // Negative quantity
              inventory.getMaximumRetailPrice(),
              BigDecimal.ZERO, // Not used for negative quantities
              BigDecimal.ZERO
          );
          purchaseItems.add(purchaseItem);
        } else {
          // Positive quantity - normal flow with stock validation
          Inventory inventory = inventoryRepository.findById(item.getId())
              .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", item.getId()));

          // Verify the inventory belongs to the shop
          if (!shopId.equals(inventory.getShopId())) {
            throw new ValidationException("Inventory lot " + item.getId() + " does not belong to shop " + shopId);
          }

          // Check stock availability
          int availableStock = inventory.getCurrentCount() != null ? inventory.getCurrentCount() : 0;
          if (availableStock < item.getQuantity()) {
            throw new InsufficientStockException("Insufficient stock for product: " + inventory.getName(),
                inventory.getBarcode(), availableStock, item.getQuantity());
          }

          // Use mapper to create PurchaseItem
          PurchaseItem purchaseItem = purchaseMapper.toPurchaseItemFromCartItem(item, inventory);
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
          // Calculate total: maximumRetailPrice * quantity
          BigDecimal mrp = item.getSellingPrice() != null ? item.getSellingPrice() : BigDecimal.ZERO;
          Integer qty = item.getQuantity() != null ? item.getQuantity() : 0;
          return mrp.multiply(BigDecimal.valueOf(qty));
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
  private TaxCalculationResult calculateTax(List<PurchaseItem> purchaseItems, String shopId) {
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
      // Calculate item subtotal (MRP * quantity)
      BigDecimal itemTotal = BigDecimal.ZERO;
      if (item.getMaximumRetailPrice() != null && item.getQuantity() != null 
          && item.getSellingPrice() != null) {
        itemTotal = item.getSellingPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
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
          BigDecimal sellingPrice = item.getSellingPrice() != null ? item.getSellingPrice() : BigDecimal.ZERO;
          return mrp.subtract(sellingPrice).multiply(BigDecimal.valueOf(item.getQuantity()));
        })
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Calculate totalAmount for a single purchase item.
   * Formula:
   * 1. Apply additionalDiscount to sellingPrice: sellingPrice * (1 - additionalDiscount/100)
   * 2. Multiply by quantity
   * 3. Add CGST and SGST: totalDiscountedAmount * (1 + cgst/100 + sgst/100)
   */
  private BigDecimal calculateItemTotalAmount(BigDecimal sellingPrice, BigDecimal additionalDiscount,
                                               Integer quantity, String cgst, String sgst) {
    if (sellingPrice == null || quantity == null || quantity <= 0) {
      return BigDecimal.ZERO;
    }
    
    // Step 1: Calculate discounted selling price per unit
    BigDecimal discountedPricePerUnit = sellingPrice;
    if (additionalDiscount != null && additionalDiscount.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal discountMultiplier = BigDecimal.ONE.subtract(
          additionalDiscount.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
      );
      discountedPricePerUnit = sellingPrice.multiply(discountMultiplier);
    }
    
    // Step 2: Multiply by quantity
    BigDecimal totalDiscountedAmount = discountedPricePerUnit.multiply(BigDecimal.valueOf(quantity));
    
    // Step 3: Add CGST and SGST
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
   * Formula: (sellingPrice * quantity) * (additionalDiscount / 100)
   */
  private BigDecimal calculateAdditionalDiscountTotal(List<PurchaseItem> items) {
    if (items == null || items.isEmpty()) {
      return BigDecimal.ZERO;
    }
    return items.stream()
        .map(item -> {
          BigDecimal sellingPrice = item.getSellingPrice() != null ? item.getSellingPrice() : BigDecimal.ZERO;
          BigDecimal additionalDiscount = item.getAdditionalDiscount() != null ? item.getAdditionalDiscount() : BigDecimal.ZERO;
          Integer quantity = item.getQuantity() != null ? item.getQuantity() : 0;
          
          // Calculate: (sellingPrice * quantity) * (additionalDiscount / 100)
          BigDecimal itemTotal = sellingPrice.multiply(BigDecimal.valueOf(quantity));
          BigDecimal discountAmount = itemTotal.multiply(additionalDiscount.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
          
          return discountAmount;
        })
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }

  private Purchase createCart(AddToCartRequest request, List<PurchaseItem> purchaseItems, String shopId, String userId, String customerId, String customerName) {
    try {
      // Calculate totals
      BigDecimal subTotal = calculateSubtotal(purchaseItems);
      TaxCalculationResult taxResult = calculateTax(purchaseItems, shopId);
      BigDecimal discountTotal = calculateTotalDiscount(purchaseItems);
      BigDecimal additionalDiscountTotal = calculateAdditionalDiscountTotal(purchaseItems);
      BigDecimal grandTotal = subTotal.add(taxResult.getTaxTotal()).subtract(discountTotal).subtract(additionalDiscountTotal);

      // Create purchase with CREATED status using mapper
      // MongoDB will auto-generate the id as ObjectId
      Purchase purchase = purchaseMapper.toPurchaseForCart(
          request, purchaseItems, subTotal, taxResult.getTaxTotal(), discountTotal, grandTotal, shopId, userId, customerId
      );
      
      // Set tax amounts, additional discount, and customerName
      purchase.setSgstAmount(taxResult.getSgstAmount());
      purchase.setCgstAmount(taxResult.getCgstAmount());
      purchase.setAdditionalDiscountTotal(additionalDiscountTotal);
      
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
                              String customerId, String customerName) {
    try {
      // Merge items - if same inventoryId exists, update quantity; otherwise add new
      List<PurchaseItem> mergedItems = new ArrayList<>(existingCart.getItems() != null ? existingCart.getItems() : new ArrayList<>());

      for (PurchaseItem newItem : newItems) {
        boolean found = false;
        for (int i = 0; i < mergedItems.size(); i++) {
          PurchaseItem existingItem = mergedItems.get(i);
          if (existingItem.getInventoryId().equals(newItem.getInventoryId())) {
            // Handle quantity update based on positive or negative
            int newQuantity = existingItem.getQuantity() + newItem.getQuantity();

            // Case 3: If negative value is more negative or equal to current item quantity, remove the item
            if (newQuantity <= 0) {
              mergedItems.remove(i);
              found = true;
              break;
            }

            // Case 1 & 2: Update quantity (positive adds, negative decreases)
            BigDecimal sellingPrice = newItem.getQuantity() > 0 ? newItem.getSellingPrice() : existingItem.getSellingPrice();
            BigDecimal newDiscount = existingItem.getMaximumRetailPrice()
                .subtract(sellingPrice)
                .multiply(BigDecimal.valueOf(newQuantity));
            // Preserve additionalDiscount from new item if adding, otherwise keep existing
            BigDecimal additionalDiscount = newItem.getQuantity() > 0 && newItem.getAdditionalDiscount() != null 
                ? newItem.getAdditionalDiscount() 
                : existingItem.getAdditionalDiscount();

            PurchaseItem updatedItem = purchaseMapper.createPurchaseItem(
                existingItem.getInventoryId(),
                existingItem.getName(),
                newQuantity,
                existingItem.getMaximumRetailPrice(),
                sellingPrice,
                newDiscount.compareTo(BigDecimal.ZERO) > 0 ? newDiscount : BigDecimal.ZERO
            );
            updatedItem.setAdditionalDiscount(additionalDiscount);
            updatedItem.setSgst(existingItem.getSgst());
            updatedItem.setCgst(existingItem.getCgst());
            // Calculate totalAmount: (sellingPrice after additionalDiscount) * quantity * (1 + cgst/100 + sgst/100)
            BigDecimal totalAmount = calculateItemTotalAmount(sellingPrice, additionalDiscount, newQuantity, 
                                                              existingItem.getCgst(), existingItem.getSgst());
            updatedItem.setTotalAmount(totalAmount);
            mergedItems.set(i, updatedItem);
            found = true;
            break;
          }
        }
        // Case 1: If item not found and quantity is positive, add new item
        if (!found && newItem.getQuantity() > 0) {
          mergedItems.add(newItem);
        }
        // Case 2 & 3: If item not found and quantity is negative, throw error (can't remove what doesn't exist)
        if (!found && newItem.getQuantity() < 0) {
          throw new ValidationException("Cannot remove item with lotId " + newItem.getInventoryId() +
              " as it does not exist in the cart");
        }
      }

      // Update business type if provided
      if (StringUtils.hasText(businessType)) {
        existingCart.setBusinessType(businessType);
      }

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
      existingCart.setItems(mergedItems);
      BigDecimal newSubTotal = calculateSubtotal(mergedItems);
      existingCart.setSubTotal(newSubTotal);
      
      TaxCalculationResult taxResult = calculateTax(mergedItems, existingCart.getShopId());
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
      if (newItem.getQuantity() > 0) {
        PurchaseItem existingItem = existingItemsMap.get(newItem.getInventoryId());
        int currentCartQuantity = existingItem != null ? existingItem.getQuantity() : 0;
        int finalQuantity = currentCartQuantity + newItem.getQuantity();

        // Get inventory to check available stock
        Inventory inventory = inventoryRepository.findById(newItem.getInventoryId())
            .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", newItem.getInventoryId()));

        // Verify inventory belongs to shop
        if (!shopId.equals(inventory.getShopId())) {
          throw new ValidationException("Inventory lot " + newItem.getInventoryId() + " does not belong to shop " + shopId);
        }

        // Check if final quantity exceeds available stock
        int availableStock = inventory.getCurrentCount() != null ? inventory.getCurrentCount() : 0;
        if (finalQuantity > availableStock) {
          throw new InsufficientStockException(
              "Insufficient stock for product: " + inventory.getName() +
                  ". Available: " + availableStock +
                  ", Requested final quantity in cart: " + finalQuantity +
                  " (current in cart: " + currentCartQuantity + ", adding: " + newItem.getQuantity() + ")",
              inventory.getBarcode(), availableStock, finalQuantity);
        }
      }
    }
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
        int currentCount = inventory.getCurrentCount() != null ? inventory.getCurrentCount() : 0;
        int soldCount = inventory.getSoldCount() != null ? inventory.getSoldCount() : 0;
        int quantity = item.getQuantity() != null ? item.getQuantity() : 0;

        // Validate that we have enough stock
        if (currentCount < quantity) {
          throw new InsufficientStockException(
              "Insufficient stock to complete purchase for product: " + inventory.getName() +
                  ". Available: " + currentCount + ", Required: " + quantity,
              inventory.getBarcode(), currentCount, quantity);
        }

        // Update inventory counts
        inventory.setCurrentCount(currentCount - quantity);
        inventory.setSoldCount(soldCount + quantity);

        // Save updated inventory
        inventoryRepository.save(inventory);
        Integer threshold = inventory.getThresholdCount() != null
          ? inventory.getThresholdCount()
          : 50;

        log.info(
          "Threshold check -> lotId={}, current={}, threshold={}",
          inventory.getId(),
          inventory.getCurrentCount(),
          threshold
        );

        if (inventory.getCurrentCount() <= threshold) {

          log.info("THRESHOLD REACHED — triggering INVENTORY_LOW event");

          InventoryEventDto dto =
            inventoryMapper.toInventoryLowEventDto(inventory, threshold);
          var eventDto = inventoryMapper.toNotificationEventDto(dto);
          eventService.recordAndBroadcastInventoryLow(eventDto);
        }

        log.info("Updated inventory for lotId: {} - decreased currentCount by {} (new: {}), increased soldCount by {} (new: {})",
            item.getInventoryId(), quantity, inventory.getCurrentCount(), quantity, inventory.getSoldCount());

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
