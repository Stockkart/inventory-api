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
import com.inventory.product.rest.dto.sale.CheckoutRequest;
import com.inventory.product.rest.dto.sale.CheckoutResponse;
import com.inventory.product.rest.dto.sale.InvalidateSaleRequest;
import com.inventory.product.rest.dto.sale.SaleStatusResponse;
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
import java.util.List;

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
  public CheckoutResponse checkout(CheckoutRequest request, HttpServletRequest httpRequest) {
    // Get shopId and userId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");
    
    if (shopId == null || userId == null) {
      throw new ValidationException("Shop ID and User ID are required. Please ensure you are authenticated.");
    }

    // Set default payment method to "CASH" if not provided
    if (!StringUtils.hasText(request.getPaymentMethod())) {
      request.setPaymentMethod("CASH");
    }

    // Input validation using CheckoutValidator
    checkoutValidator.validateCheckoutRequest(request);

    log.info("Processing checkout for shop: {}, user: {}", shopId, userId);

    try {
      // Process purchase items - throws immediately if any item fails
      List<PurchaseItem> purchaseItems = processPurchaseItems(request, request.getItems(), shopId);

      // Calculate totals
      BigDecimal subTotal = calculateSubtotal(purchaseItems);
      BigDecimal taxTotal = calculateTax(subTotal);
      BigDecimal discountTotal = calculateTotalDiscount(purchaseItems);
      BigDecimal grandTotal = subTotal.add(taxTotal).subtract(discountTotal);

      // Create and save the purchase
      Purchase purchase = createAndSavePurchase(request, purchaseItems, subTotal, taxTotal, discountTotal, grandTotal, shopId, userId);

      log.info("Successfully processed purchase with ID: {}", purchase.getId());

      // Build response with inventory lookups for barcode
      return buildCheckoutResponse(purchase);

    } catch (ValidationException | InsufficientStockException | ResourceNotFoundException e) {
      // Re-throw known business exceptions directly with their original messages
      log.warn("Checkout failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      // Database errors - log full details but provide user-friendly message
      log.error("Database error during checkout for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, 
          "Error processing checkout: " + e.getMessage(), e);
    } catch (Exception e) {
      // Unexpected errors - log full stack trace and include original message
      log.error("Unexpected error during checkout for shop: {}, user: {}", shopId, userId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, 
          "An unexpected error occurred during checkout: " + e.getMessage(), e);
    }
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
