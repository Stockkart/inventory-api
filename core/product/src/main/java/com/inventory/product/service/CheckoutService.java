package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.InsufficientStockException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Sale;
import com.inventory.product.domain.model.SaleItem;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.SaleRepository;
import com.inventory.product.rest.dto.sale.CheckoutRequest;
import com.inventory.product.rest.dto.sale.CheckoutResponse;
import com.inventory.product.rest.dto.sale.InvalidateSaleRequest;
import com.inventory.product.rest.dto.sale.SaleStatusResponse;
import com.inventory.product.rest.mapper.SaleMapper;
import com.inventory.product.validation.CheckoutValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@Transactional(readOnly = true)
public class CheckoutService {

  @Autowired
  private SaleRepository saleRepository;

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private SaleMapper saleMapper;

  @Autowired
  private CheckoutValidator checkoutValidator;

  // Moved to CheckoutValidator

  @Transactional
  public CheckoutResponse checkout(CheckoutRequest request) {
    try {
      // Input validation using CheckoutValidator
      checkoutValidator.validateCheckoutRequest(request);

      log.info("Processing checkout for shop: {}, user: {}", request.getShopId(), request.getUserId());

      // Process sale items
      List<SaleItem> saleItems = processSaleItems(request, request.getItems());

      // Calculate totals
      BigDecimal subTotal = calculateSubtotal(saleItems);
      BigDecimal taxTotal = calculateTax(subTotal);
      BigDecimal discountTotal = calculateTotalDiscount(saleItems);
      BigDecimal grandTotal = subTotal.add(taxTotal).subtract(discountTotal);

      // Create and save the sale
      Sale sale = createAndSaveSale(request, saleItems, subTotal, taxTotal, discountTotal, grandTotal);

      log.info("Successfully processed sale with ID: {}", sale.getId());

      return saleMapper.toCheckoutResponse(sale);

    } catch (ValidationException | InsufficientStockException e) {
      log.warn("Checkout validation failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error during checkout: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error processing checkout");
    } catch (Exception e) {
      log.error("Unexpected error during checkout: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred during checkout");
    }
  }

  // Validation moved to CheckoutValidator

  private List<SaleItem> processSaleItems(CheckoutRequest request, List<CheckoutRequest.CheckoutItem> items) {
    List<SaleItem> saleItems = new ArrayList<>();
    for (CheckoutRequest.CheckoutItem item : items) {
      // Validate item using CheckoutValidator
      checkoutValidator.validateCheckoutItem(item);

      // Check stock availability from inventory
      String shopId = request.getShopId();
      if (shopId == null) {
        throw new ValidationException("Shop ID is required for stock validation");
      }

      // Get inventory items for this barcode
      List<Inventory> inventories = inventoryRepository.findByShopIdAndBarcode(shopId, item.getBarcode());
      if (inventories.isEmpty()) {
        throw new ResourceNotFoundException("Inventory", "barcode", item.getBarcode());
      }

      // Get first inventory item for product info (name, sellingPrice)
      Inventory inventory = inventories.get(0);
      
      // Calculate available stock
      int availableStock = inventories.stream()
              .mapToInt(inv -> inv.getCurrentCount() != null ? inv.getCurrentCount() : 0)
              .sum();

      if (availableStock < item.getQty()) {
        throw new InsufficientStockException("Insufficient stock for product: " + inventory.getName(),
                inventory.getBarcode(), availableStock, item.getQty());
      }

      // Use mapper to create SaleItem (passing inventory instead of product)
      SaleItem saleItem = saleMapper.toSaleItem(item, inventory);
      saleItems.add(saleItem);
    }
    return saleItems;
  }

  private BigDecimal calculateSubtotal(List<SaleItem> items) {
    return items.stream()
            .map(SaleItem::getTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal calculateTax(BigDecimal subtotal) {
    // Simple tax calculation - in a real app, this would be more sophisticated
    final BigDecimal TAX_RATE = new BigDecimal("0.08"); // 8% tax rate
    return subtotal.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal calculateTotalDiscount(List<SaleItem> items) {
    return items.stream()
            .map(SaleItem::getDiscount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
  }

  private Sale createAndSaveSale(CheckoutRequest request, List<SaleItem> saleItems,
                                 BigDecimal subTotal, BigDecimal taxTotal,
                                 BigDecimal discountTotal, BigDecimal grandTotal) {
    // Use mapper to create Sale
    Sale sale = saleMapper.toSale(request, saleItems, subTotal, taxTotal, discountTotal, grandTotal);
    // Set the invoice number using the mapper's helper method
    sale.setInvoiceNo(saleMapper.generateInvoiceNo());

    return saleRepository.save(sale);
  }

  private String generateInvoiceNo() {
    String timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now());
    String random = String.format("%04d", (int) (Math.random() * 10_000));
    return "INV-" + timestamp + "-" + random;
  }

  @Transactional
  public SaleStatusResponse invalidate(String saleId, InvalidateSaleRequest request) {
    try {
      // Input validation
      if (!StringUtils.hasText(saleId)) {
        throw new ValidationException("Sale ID is required");
      }
      if (request == null) {
        throw new ValidationException("Invalidate request cannot be null");
      }

      log.info("Invalidating sale with ID: {}", saleId);

      // Find the sale
      Sale sale = saleRepository.findById(saleId)
              .orElseThrow(() -> new ResourceNotFoundException("Sale", "id", saleId));

      // Check if already invalidated
      if (!sale.isValid()) {
        log.warn("Sale {} is already invalid", saleId);
        return createSaleStatusResponse(sale);
      }

      // Invalidate the sale
      sale.setValid(false);
      sale = saleRepository.save(sale);

      log.info("Successfully invalidated sale with ID: {}", saleId);

      return createSaleStatusResponse(sale);

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Sale invalidation failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while invalidating sale: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error invalidating sale");
    } catch (Exception e) {
      log.error("Unexpected error while invalidating sale: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }

  private SaleStatusResponse createSaleStatusResponse(Sale sale) {
    return saleMapper.toStatusResponse(sale);
  }
}

