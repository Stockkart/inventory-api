package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.notifications.service.ReminderService;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.rest.dto.inventory.*;
import com.inventory.product.rest.mapper.InventoryMapper;
import com.inventory.product.validation.InventoryValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@Transactional
public class InventoryService {

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private InventoryMapper inventoryMapper;

  @Autowired
  private InventoryValidator inventoryValidator;

  @Autowired
  private ReminderService reminderService;

  public InventoryReceiptResponse create(CreateInventoryRequest request, String userId, String shopId) {
    try {
      // Input validation
      inventoryValidator.validateCreateRequest(request);

      log.debug("Creating inventory for barcode: {} in shop: {}", request.getBarcode(), shopId);

      // Map and save inventory
      Inventory inventory = inventoryMapper.toEntity(request);
      inventory.setShopId(shopId);
      inventory.setUserId(userId);
      inventory.setExpiryDate(request.getExpiryDate());

      inventory = inventoryRepository.save(inventory);
      log.info("Successfully created inventory lot: {} for product: {} in shop: {}", 
               inventory.getLotId(), inventory.getBarcode(), shopId);

      // Create reminder for expiry date asynchronously (handled by ReminderService)
      // Fire and forget - don't wait for completion
        try {
            reminderService.createReminderForInventoryCreate(
                    shopId,
                    inventory.getId(),                 // or getLotId() if that's your PK
                    request.getExpiryDate(),           // expiryDate from inventory
                    request.getReminderAt(),           // optional explicit reminderAt
                    request.getNewReminderAt(),
                    request.getReminderEndDate(),      // optional explicit endDate
                    request.getReminderNotes()         // optional notes
            );
        } catch (Exception ex) {
            // inventory creation must NOT fail because of reminder
            log.error("Failed to create reminder for inventory lot {}: {}",
                    inventory.getLotId(), ex.getMessage(), ex);
        }
      // Map to response
      InventoryReceiptResponse response = inventoryMapper.toReceiptResponse(inventory);
      // Set reminderCreated to true if expiry date exists (optimistic - actual creation happens async)
      boolean reminderCreated = inventory.getExpiryDate() != null;
      return inventoryMapper.toReceiptResponseWithReminder(inventory, reminderCreated);

    } catch (ValidationException e) {
      log.warn("Validation error in create inventory: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while creating inventory: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error processing inventory");
    } catch (Exception e) {
      log.error("Unexpected error while creating inventory: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to process inventory");
    }
  }

  public InventoryListResponse list(String shopId) {
    try {
      // Validate shopId
      inventoryValidator.validateShopId(shopId);

      log.debug("Listing inventory for shop: {}", shopId);

      List<Inventory> inventories = inventoryRepository.findByShopId(shopId);

      List<InventorySummaryDto> summaries = inventories.stream()
              .map(inventoryMapper::toSummary)
              .toList();

      return inventoryMapper.toInventoryListResponse(summaries);

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

  public InventoryListResponse search(String shopId, String query) {
    try {
      // Validate inputs
      inventoryValidator.validateShopId(shopId);
      if (query == null || query.trim().isEmpty()) {
        throw new ValidationException("Search query is required");
      }

      log.debug("Searching inventory for shop: {} with query: {}", shopId, query);

      List<Inventory> inventories = inventoryRepository.searchByShopIdAndQuery(shopId, query.trim());

      List<InventorySummaryDto> summaries = inventories.stream()
              .map(inventoryMapper::toSummary)
              .toList();

      return inventoryMapper.toInventoryListResponse(summaries);

    } catch (ValidationException e) {
      log.warn("Validation error in search inventory: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while searching inventory: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error searching inventory");
    } catch (Exception e) {
      log.error("Unexpected error while searching inventory: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to search inventory");
    }
  }

  public InventoryDetailResponse getLot(String lotId) {
    try {
      // Validate lotId
      inventoryValidator.validateLotId(lotId);

      log.debug("Getting inventory lot: {}", lotId);

      // Find inventory by ID
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
