package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.rest.dto.inventory.InventoryDetailResponse;
import com.inventory.product.rest.dto.inventory.InventoryListResponse;
import com.inventory.product.rest.dto.inventory.InventoryReceiptResponse;
import com.inventory.product.rest.dto.inventory.InventorySummaryDto;
import com.inventory.product.rest.dto.inventory.ReceiveInventoryRequest;
import com.inventory.product.rest.mapper.InventoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@Transactional
public class InventoryService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryMapper inventoryMapper;

    public InventoryReceiptResponse receive(ReceiveInventoryRequest request) {
        try {
            // Input validation
            if (request.getBarcode() == null || request.getBarcode().trim().isEmpty()) {
                throw new ValidationException("Product barcode is required");
            }
            if (request.getCount() <= 0) {
                throw new ValidationException("Count must be greater than zero");
            }
            if (request.getShopId() == null || request.getShopId().trim().isEmpty()) {
                throw new ValidationException("Shop ID is required");
            }
            if (request.getUserId() == null || request.getUserId().trim().isEmpty()) {
                throw new ValidationException("User ID is required");
            }

            Product product = productRepository.findById(request.getBarcode())
                    .orElseGet(() -> createProductFromRequest(request));
                    
            if (request.getPrice() != null) {
                product.setPrice(request.getPrice());
            }
            if (request.getName() != null) {
                product.setName(request.getName().trim());
            }
            
            productRepository.save(product);

            Inventory inventory = Inventory.builder()
                    .lotId("lot-" + UUID.randomUUID())
                    .productId(product.getBarcode())
                    .location(request.getLocation())
                    .receivedCount(request.getCount())
                    .soldCount(0)
                    .currentCount(request.getCount())
                    .receivedDate(Instant.now())
                    .expiryDate(request.getExpiryDate())
                    .shopId(request.getShopId())
                    .userId(request.getUserId())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            inventoryRepository.save(inventory);
            log.info("Received inventory for product: {} in shop: {}", product.getBarcode(), request.getShopId());

            return InventoryReceiptResponse.builder()
                    .lotId(inventory.getLotId())
                    .productId(product.getBarcode())
                    .reminderCreated(false)
                    .build();
                    
        } catch (ValidationException e) {
            log.warn("Validation error in receive inventory: {}", e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            log.error("Database error while receiving inventory: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error processing inventory");
        } catch (Exception e) {
            log.error("Unexpected error while receiving inventory: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to process inventory");
        }
    }

    private Product createProductFromRequest(ReceiveInventoryRequest request) {
        try {
            if (request.getBarcode() == null || request.getBarcode().trim().isEmpty()) {
                throw new ValidationException("Barcode is required");
            }
            if (request.getName() == null || request.getName().trim().isEmpty()) {
                throw new ValidationException("Product name is required");
            }
            
            Product product = new Product();
            product.setBarcode(request.getBarcode().trim());
            product.setName(request.getName().trim());
            product.setPrice(request.getPrice() != null ? request.getPrice() : BigDecimal.ZERO);
            product.setCompanyCode("");
            product.setProductTypeCode("");
            product.setCreatedAt(Instant.now());
            product.setUpdatedAt(Instant.now());
            return product;
            
        } catch (ValidationException e) {
            log.warn("Validation error in createProductFromRequest: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error creating product from request: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to create product");
        }
    }

    public InventoryListResponse list(String shopId) {
        try {
            if (shopId == null || shopId.trim().isEmpty()) {
                throw new ValidationException("Shop ID is required");
            }
            
            List<InventorySummaryDto> summaries = inventoryRepository.findByShopId(shopId).stream()
                    .map(inventoryMapper::toSummary)
                    .toList();
                    
            log.debug("Retrieved {} inventory items for shop {}", summaries.size(), shopId);
            
            return InventoryListResponse.builder()
                    .meta(Map.of("page", 1, "size", summaries.size()))
                    .data(summaries)
                    .build();
                    
        } catch (ValidationException e) {
            log.warn("Validation error in inventory list: {}", e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            log.error("Database error while listing inventory for shop {}: {}", shopId, e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving inventory list");
        } catch (Exception e) {
            log.error("Unexpected error while listing inventory for shop {}: {}", shopId, e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve inventory");
        }
    }

    public InventoryDetailResponse getLot(String lotId) {
        try {
            if (lotId == null || lotId.trim().isEmpty()) {
                throw new ValidationException("Lot ID is required");
            }
            
            Inventory inventory = inventoryRepository.findById(lotId)
                    .orElseThrow(() -> new ResourceNotFoundException("Inventory lot", "id", lotId));
                    
            log.debug("Retrieved inventory lot: {}", lotId);
            
            return inventoryMapper.toDetail(inventory);
            
        } catch (ResourceNotFoundException | ValidationException e) {
            log.warn("Failed to get inventory lot: {}", e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            log.error("Database error while retrieving inventory lot {}: {}", lotId, e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving inventory details");
        } catch (Exception e) {
            log.error("Unexpected error while retrieving inventory lot {}: {}", lotId, e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve inventory details");
        }
    }
}

