package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.notifications.rest.dto.CreateReminderForInventoryRequest;
import com.inventory.notifications.service.ReminderService;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.user.domain.repository.ShopVendorRepository;
import com.inventory.product.rest.dto.inventory.CreateInventoryRequest;
import com.inventory.product.rest.dto.inventory.InventoryDetailResponse;
import com.inventory.product.rest.dto.inventory.InventoryListResponse;
import com.inventory.product.rest.dto.inventory.InventoryReceiptResponse;
import com.inventory.product.rest.dto.inventory.InventorySummaryDto;
import com.inventory.product.rest.mapper.InventoryMapper;
import com.inventory.product.validation.InventoryValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

  @Autowired
  private com.inventory.user.service.VendorService vendorService;

  @Autowired
  private com.inventory.user.domain.repository.ShopVendorRepository shopVendorRepository;

  public InventoryReceiptResponse create(CreateInventoryRequest request, String userId, String shopId) {
    try {
      // Input validation
      inventoryValidator.validateCreateRequest(request);

      log.debug("Creating inventory for barcode: {} in shop: {}", request.getBarcode(), shopId);

      // Validate vendorId if provided
      if (StringUtils.hasText(request.getVendorId())) {
        validateVendorId(request.getVendorId(), shopId);
      }

      // Determine lotId: use provided lotId or generate new one
      String lotId = determineLotId(request.getLotId(), shopId);

      // Map and save inventory
      Inventory inventory = inventoryMapper.toEntity(request);
      inventory.setLotId(lotId);
      inventory.setShopId(shopId);
      inventory.setUserId(userId);
      inventory.setExpiryDate(request.getExpiryDate());
      inventory.setVendorId(request.getVendorId());

      inventory = inventoryRepository.save(inventory);
      log.info("Successfully created inventory lot: {} for product: {} in shop: {}",
          inventory.getLotId(), inventory.getBarcode(), shopId);

      // Create reminder for expiry date asynchronously (handled by ReminderService)
      // Fire and forget - don't wait for completion
      // Errors are handled inside createReminderForInventoryCreate method
      CreateReminderForInventoryRequest reminderRequest = inventoryMapper.toCreateReminderForInventoryRequest(
          request, shopId, inventory.getId());
      reminderService.createReminderForInventoryCreate(reminderRequest);
      // Map to response
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

  /**
   * Determine lotId: if provided, validate it exists for the shop; otherwise generate new one.
   *
   * @param providedLotId the lotId provided in request (can be null)
   * @param shopId the shop ID
   * @return the lotId to use
   */
  private String determineLotId(String providedLotId, String shopId) {
    // If lotId is provided, validate it exists for this shop
    if (StringUtils.hasText(providedLotId)) {
      List<Inventory> existingInventories = inventoryRepository.findByShopIdAndLotId(shopId, providedLotId.trim());
      if (!existingInventories.isEmpty()) {
        // LotId exists, reuse it
        log.debug("Reusing existing lotId: {} for shop: {}", providedLotId, shopId);
        return providedLotId.trim();
      } else {
        // LotId provided but doesn't exist - this is okay, we'll create a new lot with this ID
        log.debug("Using provided lotId: {} for shop: {} (new lot)", providedLotId, shopId);
        return providedLotId.trim();
      }
    }

    // Generate new lotId
    String newLotId = generateLotId();
    log.debug("Generated new lotId: {} for shop: {}", newLotId, shopId);
    return newLotId;
  }

  /**
   * Generate a new lotId.
   * Format: LOT-{timestamp}-{randomUUID}
   *
   * @return generated lotId
   */
  private String generateLotId() {
    String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    String random = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    return "LOT-" + timestamp + "-" + random;
  }

  /**
   * Validate that vendorId exists and is linked to the shop.
   *
   * @param vendorId the vendor ID to validate
   * @param shopId the shop ID
   */
  private void validateVendorId(String vendorId, String shopId) {
    if (!shopVendorRepository.existsByShopIdAndVendorId(shopId, vendorId.trim())) {
      throw new ValidationException("Vendor with ID " + vendorId + " is not associated with shop " + shopId);
    }
  }
}
