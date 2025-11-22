package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.InsufficientStockException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.Sale;
import com.inventory.product.domain.model.SaleItem;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.domain.repository.SaleRepository;
import com.inventory.product.rest.dto.sale.CheckoutRequest;
import com.inventory.product.rest.dto.sale.CheckoutResponse;
import com.inventory.product.rest.dto.sale.InvalidateSaleRequest;
import com.inventory.product.rest.dto.sale.SaleStatusResponse;
import com.inventory.product.rest.mapper.SaleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional(readOnly = true)
public class CheckoutService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SaleRepository saleRepository;
    
    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private SaleMapper saleMapper;
    
    private static final int MAX_QUANTITY = 1000; // Maximum allowed quantity per item
    private static final int MAX_ITEMS_PER_SALE = 100; // Maximum allowed items per sale

    @Transactional
    public CheckoutResponse checkout(CheckoutRequest request) {
        try {
            // Input validation
            validateCheckoutRequest(request);
            
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

    private void validateCheckoutRequest(CheckoutRequest request) {
        if (request == null) {
            throw new ValidationException("Checkout request cannot be null");
        }
        if (!StringUtils.hasText(request.getShopId())) {
            throw new ValidationException("Shop ID is required");
        }
        if (!StringUtils.hasText(request.getUserId())) {
            throw new ValidationException("User ID is required");
        }
        if (CollectionUtils.isEmpty(request.getItems())) {
            throw new ValidationException("At least one item is required for checkout");
        }
        if (request.getItems().size() > MAX_ITEMS_PER_SALE) {
            throw new ValidationException("Exceeded maximum number of items per sale (" + MAX_ITEMS_PER_SALE + ")");
        }
    }
    
    private List<SaleItem> processSaleItems(CheckoutRequest request, List<CheckoutRequest.CheckoutItem> items) {
        return items.stream()
            .map(item -> {
                // Validate item
                if (item.getQty() == null || item.getQty() <= 0) {
                    throw new ValidationException("Invalid quantity for item: " + item.getBarcode());
                }
                if (item.getQty() > MAX_QUANTITY) {
                    throw new ValidationException("Maximum quantity per item is " + MAX_QUANTITY);
                }
                
                // Get product and validate stock
                Product product = productRepository.findById(item.getBarcode())
                        .orElseThrow(() -> new ResourceNotFoundException("Product", "barcode", item.getBarcode()));
                
                // Check stock availability from inventory
                String shopId = request.getShopId();
                if (shopId == null) {
                    throw new ValidationException("Shop ID is required for stock validation");
                }
                
                List<Inventory> inventories = inventoryRepository.findByShopIdAndProductId(shopId, product.getBarcode());
                int availableStock = inventories.stream()
                        .mapToInt(inv -> inv.getCurrentCount() != null ? inv.getCurrentCount() : 0)
                        .sum();
                
                if (availableStock < item.getQty()) {
                    throw new InsufficientStockException("Insufficient stock for product: " + product.getName(), 
                            product.getBarcode(), availableStock, item.getQty());
                }
                
                // Calculate item totals
                BigDecimal qty = BigDecimal.valueOf(item.getQty());
                BigDecimal discount = item.getDiscount() != null ? 
                        BigDecimal.valueOf(item.getDiscount()).setScale(2, RoundingMode.HALF_UP) : 
                        BigDecimal.ZERO;
                
                // Ensure discount is not negative and not greater than item price
                if (discount.compareTo(BigDecimal.ZERO) < 0) {
                    discount = BigDecimal.ZERO;
                }
                
                BigDecimal maxDiscount = product.getPrice().multiply(qty);
                if (discount.compareTo(maxDiscount) > 0) {
                    discount = maxDiscount;
                }
                
                BigDecimal total = product.getPrice().multiply(qty).subtract(discount);
                
                return SaleItem.builder()
                        .productId(product.getBarcode())
                        .productName(product.getName())
                        .quantity(item.getQty())
                        .salePrice(product.getPrice())
                        .discount(discount)
                        .total(total)
                        .build();
            })
            .collect(Collectors.toList());
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
        Sale sale = Sale.builder()
                .id("sale-" + UUID.randomUUID())
                .invoiceId(UUID.randomUUID().toString())
                .invoiceNo(generateInvoiceNo())
                .shopId(request.getShopId())
                .userId(request.getUserId())
                .items(saleItems)
                .subTotal(subTotal)
                .taxTotal(taxTotal)
                .discountTotal(discountTotal)
                .grandTotal(grandTotal)
                .soldAt(Instant.now())
                .valid(true)
                .paymentMethod(request.getPaymentMethod())
                .build();
                
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
        return SaleStatusResponse.builder()
                .saleId(sale.getId())
                .valid(sale.isValid())
                .build();
    }
}

