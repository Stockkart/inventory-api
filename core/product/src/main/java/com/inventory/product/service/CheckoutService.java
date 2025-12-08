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
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.rest.dto.sale.AddToCartRequest;
import com.inventory.product.rest.dto.sale.AddToCartResponse;
import com.inventory.product.rest.dto.sale.CheckoutRequest;
import com.inventory.product.rest.dto.sale.CheckoutResponse;
import com.inventory.product.rest.dto.sale.InvalidateSaleRequest;
import com.inventory.product.rest.dto.sale.PurchaseListResponse;
import com.inventory.product.rest.dto.sale.PurchaseSummaryDto;
import com.inventory.product.rest.dto.sale.SaleStatusResponse;
import com.inventory.product.rest.dto.sale.UpdatePurchaseStatusRequest;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

      // Process new items
      List<PurchaseItem> newItems = processCartItems(request.getItems(), shopId);

      Purchase purchase;
      if (existingCart != null) {
        // Validate stock availability before updating cart (check final quantities)
        validateStockAvailabilityForCartUpdate(existingCart, newItems, shopId);
        
        // Update existing cart - merge items
        log.info("Updating existing cart with ID: {}", existingCart.getId());
        purchase = updateCart(existingCart, newItems, request.getBusinessType(), 
                request.getCustomerName(), request.getCustomerAddress(), request.getCustomerPhone());
      } else {
        // Create new cart (stock validation already done in processCartItems for positive quantities)
        log.info("Creating new cart");
        purchase = createCart(request, newItems, shopId, userId);
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
          Inventory inventory = inventoryRepository.findById(item.getLotId())
                  .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", item.getLotId()));
          
          if (!shopId.equals(inventory.getShopId())) {
            throw new ValidationException("Inventory lot " + item.getLotId() + " does not belong to shop " + shopId);
          }
          
          // For negative quantities, create a PurchaseItem with negative quantity
          // The updateCart method will handle the logic
          PurchaseItem purchaseItem = purchaseMapper.createPurchaseItem(
                  item.getLotId(),
                  inventory.getName(),
                  item.getQuantity(), // Negative quantity
                  inventory.getMaximumRetailPrice(),
                  BigDecimal.ZERO, // Not used for negative quantities
                  BigDecimal.ZERO
          );
          purchaseItems.add(purchaseItem);
        } else {
          // Positive quantity - normal flow with stock validation
          Inventory inventory = inventoryRepository.findById(item.getLotId())
                  .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", item.getLotId()));

          // Verify the inventory belongs to the shop
          if (!shopId.equals(inventory.getShopId())) {
            throw new ValidationException("Inventory lot " + item.getLotId() + " does not belong to shop " + shopId);
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
        log.warn("Item validation failed for lotId: {} - {}", item.getLotId(), e.getMessage());
        throw e;
      } catch (Exception e) {
        log.error("Unexpected error processing item with lotId: {}", item.getLotId(), e);
        throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, 
            "Error processing item with lotId " + item.getLotId() + ": " + e.getMessage(), e);
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
              BigDecimal mrp = item.getMaximumRetailPrice() != null ? item.getMaximumRetailPrice() : BigDecimal.ZERO;
              Integer qty = item.getQuantity() != null ? item.getQuantity() : 0;
              return mrp.multiply(BigDecimal.valueOf(qty));
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal calculateTax(BigDecimal subtotal) {
    // Simple tax calculation - in a real app, this would be more sophisticated
    final BigDecimal TAX_RATE = new BigDecimal("0.08"); // 8% tax rate
    return subtotal.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
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

  private Purchase createCart(AddToCartRequest request, List<PurchaseItem> purchaseItems, String shopId, String userId) {
    try {
      // Calculate totals
      BigDecimal subTotal = calculateSubtotal(purchaseItems);
      BigDecimal taxTotal = calculateTax(subTotal);
      BigDecimal discountTotal = calculateTotalDiscount(purchaseItems);
      BigDecimal grandTotal = subTotal.add(taxTotal).subtract(discountTotal);

      // Create purchase with CREATED status using mapper
      // MongoDB will auto-generate the id as ObjectId
      Purchase purchase = purchaseMapper.toPurchaseForCart(
              request, purchaseItems, subTotal, taxTotal, discountTotal, grandTotal, shopId, userId
      );

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

  private Purchase updateCart(Purchase existingCart, List<PurchaseItem> newItems, String businessType,
                              String customerName, String customerAddress, String customerPhone) {
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
            
            mergedItems.set(i, purchaseMapper.createPurchaseItem(
                    existingItem.getInventoryId(),
                    existingItem.getName(),
                    newQuantity,
                    existingItem.getMaximumRetailPrice(),
                    sellingPrice,
                    newDiscount.compareTo(BigDecimal.ZERO) > 0 ? newDiscount : BigDecimal.ZERO
            ));
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

      // Update customer information if provided
      if (StringUtils.hasText(customerName)) {
        existingCart.setCustomerName(customerName);
      }
      if (StringUtils.hasText(customerAddress)) {
        existingCart.setCustomerAddress(customerAddress);
      }
      if (StringUtils.hasText(customerPhone)) {
        existingCart.setCustomerPhone(customerPhone);
      }

      // Recalculate totals
      existingCart.setItems(mergedItems);
      existingCart.setSubTotal(calculateSubtotal(mergedItems));
      existingCart.setTaxTotal(calculateTax(existingCart.getSubTotal()));
      existingCart.setDiscountTotal(calculateTotalDiscount(mergedItems));
      existingCart.setGrandTotal(existingCart.getSubTotal()
              .add(existingCart.getTaxTotal())
              .subtract(existingCart.getDiscountTotal()));

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
