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
import com.inventory.product.rest.dto.sale.SaleStatusResponse;
import com.inventory.product.rest.dto.sale.UpdatePurchaseStatusRequest;
import com.inventory.product.rest.mapper.PurchaseMapper;
import com.inventory.product.validation.CheckoutValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
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
        purchase = updateCart(existingCart, newItems, request.getBusinessType());
      } else {
        // Create new cart (stock validation already done in processCartItems for positive quantities)
        log.info("Creating new cart");
        purchase = createCart(request, newItems, shopId, userId);
      }

      log.info("Successfully updated cart with ID: {}", purchase.getId());

      // Build response
      return buildAddToCartResponse(purchase);

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
    if (request == null) {
      throw new ValidationException("Update purchase status request cannot be null");
    }
    if (request.getStatus() == null) {
      throw new ValidationException("Status is required");
    }
    if (request.getStatus() != PurchaseStatus.PENDING && request.getStatus() != PurchaseStatus.COMPLETED) {
      throw new ValidationException("Status must be either PENDING or COMPLETED");
    }

    // Set default payment method to "CASH" if not provided
    if (!StringUtils.hasText(request.getPaymentMethod())) {
      request.setPaymentMethod("CASH");
    }

    log.info("Updating purchase status to {} for shop: {}, user: {}", request.getStatus(), shopId, userId);

    try {
      // Find existing cart (CREATED or PENDING status)
      List<PurchaseStatus> statuses = List.of(PurchaseStatus.CREATED, PurchaseStatus.PENDING);
      Purchase purchase = purchaseRepository.findByUserIdAndShopIdAndStatusIn(userId, shopId, statuses)
              .orElseThrow(() -> new ResourceNotFoundException("Cart", "userId and shopId", 
                  "No active cart found for user " + userId + " and shop " + shopId));

      // Update status and payment method
      purchase.setStatus(request.getStatus());
      purchase.setPaymentMethod(request.getPaymentMethod());
      purchase = purchaseRepository.save(purchase);

      log.info("Successfully updated purchase status to {} for purchase ID: {}", request.getStatus(), purchase.getId());

      // Build response
      return buildCheckoutResponse(purchase);

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
          PurchaseItem purchaseItem = PurchaseItem.builder()
                  .inventoryId(item.getLotId())
                  .name(inventory.getName())
                  .quantity(item.getQuantity()) // Negative quantity
                  .maximumRetailPrice(inventory.getMaximumRetailPrice())
                  .sellingPrice(BigDecimal.ZERO) // Not used for negative quantities
                  .discount(BigDecimal.ZERO)
                  .build();
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

  private List<PurchaseItem> processPurchaseItems(CheckoutRequest request, List<CheckoutRequest.CheckoutItem> items, String shopId) {
    List<PurchaseItem> purchaseItems = new ArrayList<>();
    
    for (CheckoutRequest.CheckoutItem item : items) {
      try {
        // Validate item using CheckoutValidator - throws ValidationException immediately if invalid
        checkoutValidator.validateCheckoutItem(item);

        // Get inventory by lotId - throws ResourceNotFoundException immediately if not found
        Inventory inventory = inventoryRepository.findById(item.getLotId())
                .orElseThrow(() -> new ResourceNotFoundException("Inventory", "lotId", item.getLotId()));

        // Verify the inventory belongs to the shop - throws ValidationException immediately if mismatch
        if (!shopId.equals(inventory.getShopId())) {
          throw new ValidationException("Inventory lot " + item.getLotId() + " does not belong to shop " + shopId);
        }

        // Check stock availability - throws InsufficientStockException immediately if insufficient
        int availableStock = inventory.getCurrentCount() != null ? inventory.getCurrentCount() : 0;
        if (availableStock < item.getQuantity()) {
          throw new InsufficientStockException("Insufficient stock for product: " + inventory.getName(),
                  inventory.getBarcode(), availableStock, item.getQuantity());
        }

        // Use mapper to create PurchaseItem with sellingPrice from request
        PurchaseItem purchaseItem = purchaseMapper.toPurchaseItem(item, inventory);
        purchaseItems.add(purchaseItem);
        
      } catch (ValidationException | InsufficientStockException | ResourceNotFoundException e) {
        // Re-throw immediately - don't continue processing other items
        log.warn("Item validation failed for lotId: {} - {}", item.getLotId(), e.getMessage());
        throw e;
      } catch (Exception e) {
        // Catch any unexpected errors during item processing
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

      // Create purchase with CREATED status
      Purchase purchase = Purchase.builder()
              .id("purchase-" + java.util.UUID.randomUUID())
              .invoiceId(java.util.UUID.randomUUID().toString())
              .invoiceNo(purchaseMapper.generateInvoiceNo())
              .businessType(request.getBusinessType())
              .userId(userId)
              .shopId(shopId)
              .items(purchaseItems)
              .subTotal(subTotal)
              .taxTotal(taxTotal)
              .discountTotal(discountTotal)
              .grandTotal(grandTotal)
              .soldAt(java.time.Instant.now())
              .valid(true)
              .status(PurchaseStatus.CREATED)
              .build();

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

  private Purchase updateCart(Purchase existingCart, List<PurchaseItem> newItems, String businessType) {
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
            
            mergedItems.set(i, PurchaseItem.builder()
                    .inventoryId(existingItem.getInventoryId())
                    .name(existingItem.getName())
                    .quantity(newQuantity)
                    .maximumRetailPrice(existingItem.getMaximumRetailPrice())
                    .sellingPrice(sellingPrice)
                    .discount(newDiscount.compareTo(BigDecimal.ZERO) > 0 ? newDiscount : BigDecimal.ZERO)
                    .build());
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

  private Purchase updateCartForCheckout(Purchase existingCart, List<PurchaseItem> newItems, 
                                         String businessType, String paymentMethod) {
    try {
      // Update cart with new items
      Purchase updatedCart = updateCart(existingCart, newItems, businessType);
      
      // Change status to PENDING
      updatedCart.setStatus(PurchaseStatus.PENDING);
      updatedCart.setPaymentMethod(paymentMethod);
      
      return purchaseRepository.save(updatedCart);
    } catch (Exception e) {
      log.error("Error updating cart for checkout: {}", existingCart.getId(), e);
      throw e;
    }
  }

  private Purchase createAndSavePurchase(CheckoutRequest request, List<PurchaseItem> purchaseItems,
                                         BigDecimal subTotal, BigDecimal taxTotal,
                                         BigDecimal discountTotal, BigDecimal grandTotal,
                                         String shopId, String userId) {
    try {
      // Use mapper to create Purchase
      Purchase purchase = purchaseMapper.toPurchase(request, purchaseItems, subTotal, taxTotal, discountTotal, grandTotal);
      
      // Set businessType from request
      purchase.setBusinessType(request.getBusinessType());
      
      // Set shopId and userId
      purchase.setShopId(shopId);
      purchase.setUserId(userId);
      
      // Set payment method (defaults to "CASH" if not provided, already set in checkout method)
      purchase.setPaymentMethod(request.getPaymentMethod());
      
      // Set the invoice number using the mapper's helper method
      purchase.setInvoiceNo(purchaseMapper.generateInvoiceNo());
      
      // Set status to PENDING by default for new purchases
      purchase.setStatus(PurchaseStatus.PENDING);

      return purchaseRepository.save(purchase);
    } catch (DataAccessException e) {
      log.error("Database error while saving purchase for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, 
          "Error saving purchase: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected error while creating purchase for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, 
          "Error creating purchase: " + e.getMessage(), e);
    }
  }

  @Transactional
  public SaleStatusResponse invalidate(String purchaseId, InvalidateSaleRequest request) {
    try {
      // Input validation
      if (!StringUtils.hasText(purchaseId)) {
        throw new ValidationException("Purchase ID is required");
      }
      if (request == null) {
        throw new ValidationException("Invalidate request cannot be null");
      }

      log.info("Invalidating purchase with ID: {}", purchaseId);

      // Find the purchase
      Purchase purchase = purchaseRepository.findById(purchaseId)
              .orElseThrow(() -> new ResourceNotFoundException("Purchase", "id", purchaseId));

      // Check if already invalidated
      if (!purchase.isValid()) {
        log.warn("Purchase {} is already invalid", purchaseId);
        return createPurchaseStatusResponse(purchase);
      }

      // Invalidate the purchase
      purchase.setValid(false);
      purchase = purchaseRepository.save(purchase);

      log.info("Successfully invalidated purchase with ID: {}", purchaseId);

      return createPurchaseStatusResponse(purchase);

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Purchase invalidation failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while invalidating purchase: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error invalidating purchase");
    } catch (Exception e) {
      log.error("Unexpected error while invalidating purchase: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }

  private SaleStatusResponse createPurchaseStatusResponse(Purchase purchase) {
    return purchaseMapper.toStatusResponse(purchase);
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

      return buildAddToCartResponse(cart);

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

  private AddToCartResponse buildAddToCartResponse(Purchase purchase) {
    try {
      if (purchase == null) {
        throw new ValidationException("Purchase cannot be null when building response");
      }

      return AddToCartResponse.builder()
              .purchaseId(purchase.getId())
              .invoiceId(purchase.getInvoiceId())
              .invoiceNo(purchase.getInvoiceNo())
              .businessType(purchase.getBusinessType())
              .userId(purchase.getUserId())
              .shopId(purchase.getShopId())
              .items(purchase.getItems() != null ? purchase.getItems() : List.of())
              .subTotal(purchase.getSubTotal())
              .taxTotal(purchase.getTaxTotal())
              .discountTotal(purchase.getDiscountTotal())
              .grandTotal(purchase.getGrandTotal())
              .status(purchase.getStatus())
              .build();
    } catch (BaseException e) {
      throw e;
    } catch (Exception e) {
      String purchaseId = purchase != null ? purchase.getId() : "unknown";
      log.error("Unexpected error building add to cart response for purchase: {}", purchaseId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, 
          "Error building add to cart response: " + e.getMessage(), e);
    }
  }

  private CheckoutResponse buildCheckoutResponse(Purchase purchase) {
    try {
      if (purchase == null) {
        throw new ValidationException("Purchase cannot be null when building response");
      }

      return CheckoutResponse.builder()
              .invoiceId(purchase.getInvoiceId())
              .invoiceNo(purchase.getInvoiceNo())
              .businessType(purchase.getBusinessType())
              .userId(purchase.getUserId())
              .shopId(purchase.getShopId())
              .items(purchase.getItems() != null ? purchase.getItems() : List.of())
              .subTotal(purchase.getSubTotal())
              .taxTotal(purchase.getTaxTotal())
              .discountTotal(purchase.getDiscountTotal())
              .grandTotal(purchase.getGrandTotal())
              .paymentMethod(purchase.getPaymentMethod())
              .status(purchase.getStatus())
              .build();
    } catch (BaseException e) {
      // Re-throw BaseException and its subclasses (ValidationException, etc.)
      throw e;
    } catch (Exception e) {
      String purchaseId = purchase != null ? purchase.getId() : "unknown";
      log.error("Unexpected error building checkout response for purchase: {}", purchaseId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, 
          "Error building checkout response: " + e.getMessage(), e);
    }
  }
}
