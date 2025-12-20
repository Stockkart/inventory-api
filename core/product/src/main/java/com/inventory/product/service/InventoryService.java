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
import com.inventory.product.domain.repository.InventoryRepository.LotSummaryProjection;
import com.inventory.product.rest.dto.inventory.CreateInventoryRequest;
import com.inventory.product.rest.dto.inventory.InventoryDetailResponse;
import com.inventory.product.rest.dto.inventory.InventoryListResponse;
import com.inventory.product.rest.dto.inventory.InventoryReceiptResponse;
import com.inventory.product.rest.dto.inventory.InventorySummaryDto;
import com.inventory.product.rest.dto.inventory.LotDetailDto;
import com.inventory.product.rest.dto.inventory.LotListResponse;
import com.inventory.product.rest.dto.inventory.LotSummaryDto;
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
import java.util.Map;
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
   * List all lots for a shop.
   *
   * @param shopId the shop ID
   * @param searchQuery optional search query to filter lots
   * @return list of lot summaries
   */
  @Transactional(readOnly = true)
  public LotListResponse listLots(String shopId, String searchQuery) {
    try {
      // Validate shopId
      inventoryValidator.validateShopId(shopId);

      log.debug("Listing lots for shop: {} with search: {}", shopId, searchQuery);

      // Get all inventories for the shop
      List<Inventory> inventories = inventoryRepository.findByShopId(shopId);

      // Filter by search query if provided
      if (StringUtils.hasText(searchQuery)) {
        String searchLower = searchQuery.trim().toLowerCase();
        inventories = inventories.stream()
            .filter(inv -> inv.getLotId() != null && 
                inv.getLotId().toLowerCase().contains(searchLower))
            .toList();
      }

      // Group by lotId and build summaries
      Map<String, List<Inventory>> lotGroups = inventories.stream()
          .filter(inv -> inv.getLotId() != null && !inv.getLotId().trim().isEmpty())
          .collect(java.util.stream.Collectors.groupingBy(Inventory::getLotId));

      List<LotSummaryDto> summaries = lotGroups.entrySet().stream()
          .map(entry -> {
            String lotId = entry.getKey();
            List<Inventory> lotInventories = entry.getValue();

            // Calculate summary data
            Integer productCount = lotInventories.size();
            Instant createdAt = lotInventories.stream()
                .map(Inventory::getCreatedAt)
                .filter(created -> created != null)
                .min(Instant::compareTo)
                .orElse(Instant.now());
            Instant lastUpdated = lotInventories.stream()
                .map(Inventory::getUpdatedAt)
                .filter(updated -> updated != null)
                .max(Instant::compareTo)
                .orElse(createdAt);
            String firstProductName = lotInventories.stream()
                .map(Inventory::getName)
                .filter(name -> name != null && !name.trim().isEmpty())
                .findFirst()
                .orElse(null);

            LotSummaryDto dto = new LotSummaryDto();
            dto.setLotId(lotId);
            dto.setProductCount(productCount);
            dto.setCreatedAt(createdAt);
            dto.setLastUpdated(lastUpdated);
            dto.setFirstProductName(firstProductName);
            return dto;
          })
          .sorted((a, b) -> {
            // Sort by lastUpdated descending
            if (a.getLastUpdated() == null && b.getLastUpdated() == null) return 0;
            if (a.getLastUpdated() == null) return 1;
            if (b.getLastUpdated() == null) return -1;
            return b.getLastUpdated().compareTo(a.getLastUpdated());
          })
          .toList();

      LotListResponse response = new LotListResponse();
      response.setData(summaries);
      response.setMeta(null);

      return response;

    } catch (ValidationException e) {
      log.warn("Validation error in list lots: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while listing lots: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving lots list");
    } catch (Exception e) {
      log.error("Unexpected error while listing lots: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve lots list");
    }
  }

  /**
   * Get details of a specific lot including all products in that lot.
   *
   * @param lotId the lot ID
   * @param shopId the shop ID (for authorization)
   * @return lot details with all products
   */
  @Transactional(readOnly = true)
  public LotDetailDto getLotDetails(String lotId, String shopId) {
    try {
      // Validate inputs
      inventoryValidator.validateLotId(lotId);
      inventoryValidator.validateShopId(shopId);

      log.debug("Getting lot details for lotId: {} in shop: {}", lotId, shopId);

      // Find all inventories with this lotId in the shop
      List<Inventory> inventories = inventoryRepository.findByShopIdAndLotId(shopId, lotId);

      if (inventories.isEmpty()) {
        throw new ResourceNotFoundException("Lot", "lotId", lotId);
      }

      // Map to summary DTOs
      List<InventorySummaryDto> items = inventories.stream()
          .map(inventoryMapper::toSummary)
          .toList();

      // Calculate totals
      Integer totalProductCount = inventories.size();
      Integer totalCurrentCount = inventories.stream()
          .mapToInt(inv -> inv.getCurrentCount() != null ? inv.getCurrentCount() : 0)
          .sum();

      // Get timestamps from first inventory
      Inventory firstInventory = inventories.get(0);
      Instant createdAt = firstInventory.getCreatedAt() != null 
          ? firstInventory.getCreatedAt() 
          : Instant.now();
      
      Instant lastUpdated = inventories.stream()
          .map(Inventory::getUpdatedAt)
          .filter(updatedAt -> updatedAt != null)
          .max(Instant::compareTo)
          .orElse(createdAt);

      // Build response
      LotDetailDto lotDetail = new LotDetailDto();
      lotDetail.setLotId(lotId);
      lotDetail.setShopId(shopId);
      lotDetail.setCreatedAt(createdAt);
      lotDetail.setLastUpdated(lastUpdated);
      lotDetail.setItems(items);
      lotDetail.setTotalProductCount(totalProductCount);
      lotDetail.setTotalCurrentCount(totalCurrentCount);

      return lotDetail;

    } catch (ResourceNotFoundException e) {
      log.warn("Lot not found: {} in shop: {}", lotId, shopId);
      throw e;
    } catch (ValidationException e) {
      log.warn("Validation error in get lot details: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting lot details: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving lot details");
    } catch (Exception e) {
      log.error("Unexpected error while getting lot details: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve lot details");
    }
  }

  /**
   * Search lots by lotId query.
   *
   * @param shopId the shop ID
   * @param query the search query
   * @return list of matching lot summaries
   */
  @Transactional(readOnly = true)
  public LotListResponse searchLots(String shopId, String query) {
    try {
      // Validate inputs
      inventoryValidator.validateShopId(shopId);
      if (query == null || query.trim().isEmpty()) {
        throw new ValidationException("Search query is required");
      }

      log.debug("Searching lots for shop: {} with query: {}", shopId, query);

      return listLots(shopId, query.trim());

    } catch (ValidationException e) {
      log.warn("Validation error in search lots: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while searching lots: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error searching lots");
    } catch (Exception e) {
      log.error("Unexpected error while searching lots: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to search lots");
    }
  }

  /**
   * Determine lotId: if provided, validate it exists for the shop or validate format and uniqueness;
   * otherwise generate new one.
   *
   * @param providedLotId the lotId provided in request (can be null)
   * @param shopId the shop ID
   * @return the lotId to use
   */
  private String determineLotId(String providedLotId, String shopId) {
    // If lotId is provided, validate format and check uniqueness per shop
    if (StringUtils.hasText(providedLotId)) {
      String trimmedLotId = providedLotId.trim();
      
      // Validate format
      inventoryValidator.validateLotIdFormat(trimmedLotId);
      
      return trimmedLotId;
    }

    // Generate new lotId - ensure uniqueness per shop
    String newLotId = generateUniqueLotId(shopId);
    log.debug("Generated new lotId: {} for shop: {}", newLotId, shopId);
    return newLotId;
  }

  /**
   * Generate a unique lotId for the shop.
   * Format: LOT-{timestamp}-{randomUUID}
   * Ensures uniqueness within the shop.
   *
   * @param shopId the shop ID
   * @return generated unique lotId
   */
  private String generateUniqueLotId(String shopId) {
    String timestamp = java.time.LocalDateTime.now().format(
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
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
