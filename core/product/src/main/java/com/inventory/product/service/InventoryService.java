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

            // Find or create product
            Product product = productRepository.findById(request.getBarcode())
                    .orElseGet(() -> inventoryMapper.createProductFromRequest(request));
            
            // Update product details if needed
            if (request.getPrice() != null && !request.getPrice().equals(product.getPrice())) {
                product.setPrice(request.getPrice());
            }
            if (request.getName() != null && !request.getName().trim().equals(product.getName())) {
                product.setName(request.getName().trim());
            }
            
            // Save product if it has a barcode
            if (product.getBarcode() != null) {
                product = productRepository.save(product);
            }

            // Map and save inventory
            Inventory inventory = inventoryMapper.toEntity(request);
            inventory.setExpiryDate(request.getExpiryDate());
            inventory.setShopId(request.getShopId());
            inventory.setUserId(request.getUserId());
            
            inventory = inventoryRepository.save(inventory);
            log.info("Received inventory for product: {} in shop: {}", product.getBarcode(), request.getShopId());

            // Map to response
            return inventoryMapper.toReceiptResponse(inventory);
                    
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
            
            List<Inventory> inventories = inventoryRepository.findByShopId(shopId);
            
            List<InventorySummaryDto> summaries = inventories.stream()
                    .map(inventory -> {
                        // Map inventory to summary DTO
                        return inventoryMapper.toSummary(inventory);
                    })
                    .toList();
                    
            return InventoryListResponse.builder()
                    .data(summaries)
                    .build();
            
        } catch (ValidationException e) {
            log.warn("Validation error in list inventory: {}", e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            log.error("Database error while listing inventory: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving inventory list");
        } catch (Exception e) {
            log.error("Unexpected error while listing inventory: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve inventory list");
        }
    }

    public InventoryDetailResponse getLot(String lotId) {
        try {
            if (lotId == null || lotId.trim().isEmpty()) {
                throw new ValidationException("Lot ID is required");
            }
            
            // Find inventory by ID (using lotId as the ID since it's the @Id field)
            Inventory inventory = inventoryRepository.findById(lotId)
                    .orElseThrow(() -> new ResourceNotFoundException("Inventory lot", "lotId", lotId));
                    
            return inventoryMapper.toDetail(inventory);
            
        } catch (ResourceNotFoundException e) {
            log.warn("Inventory lot not found: {}", lotId);
            throw e;
        } catch (ValidationException e) {
            log.warn("Validation error in get lot: {}", e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            log.error("Database error while getting inventory lot: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving inventory lot");
        } catch (Exception e) {
            log.error("Unexpected error while getting inventory lot: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve inventory lot");
        }
    }
}
