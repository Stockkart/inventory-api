package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.notifications.rest.dto.CreateReminderForInventoryRequest;
import com.inventory.notifications.service.ReminderService;
import com.inventory.ocr.service.InvoiceParserService;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.SchemeType;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.rest.dto.inventory.*;
import java.util.ArrayList;
import com.inventory.user.domain.repository.ShopVendorRepository;
import com.inventory.product.domain.repository.InventoryRepository.LotSummaryProjection;
import com.inventory.product.rest.mapper.InventoryMapper;
import com.inventory.product.validation.InventoryValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  @Autowired
  private InvoiceParserService invoiceParserService;

  @Autowired
  private ParsedInventoryMapper parsedInventoryMapper;

  /**
   * Parse invoice image and extract inventory items using OCR.
   * Validates the image file and converts parsed items to CreateInventoryItemRequest format.
   *
   * @param image the invoice image file to process
   * @return ParsedInventoryListResponse containing list of CreateInventoryItemRequest items
   * @throws ValidationException if image file is empty or invalid content type
   * @throws BaseException if there's an error reading the file or parsing the invoice
   */
  @Transactional(readOnly = true)
  public ParsedInventoryListResponse parseInvoiceImage(MultipartFile image) {
    log.info("Processing invoice parsing request for image: {}, size: {} bytes",
        image.getOriginalFilename(), image.getSize());

    // Validate file
    if (image.isEmpty()) {
      throw new ValidationException("Image file is empty");
    }

    // Validate content type
    String contentType = image.getContentType();
    if (contentType == null ||
        (!contentType.startsWith("image/") && !contentType.equals("application/octet-stream"))) {
      throw new ValidationException("File must be an image");
    }

    try {
      byte[] imageBytes = image.getBytes();
      return parseInvoiceImageFromBytes(imageBytes);
    } catch (IOException e) {
      log.error("Error reading image file: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error reading image file: " + e.getMessage(), e);
    }
  }

  /**
   * Parse invoice image from raw bytes (e.g. when bytes are read synchronously before async processing).
   * Use this when processing in a background thread to avoid accessing MultipartFile temp files after request completes.
   *
   * @param imageBytes the invoice image bytes
   * @return ParsedInventoryListResponse containing list of CreateInventoryItemRequest items
   * @throws ValidationException if image bytes are empty
   * @throws BaseException if there's an error parsing the invoice
   */
  @Transactional(readOnly = true)
  public ParsedInventoryListResponse parseInvoiceImageFromBytes(byte[] imageBytes) {
    if (imageBytes == null || imageBytes.length == 0) {
      throw new ValidationException("Image bytes are empty");
    }

    try {
      List<com.inventory.ocr.dto.ParsedInventoryItem> parsedItems =
          invoiceParserService.parseInvoiceImage(imageBytes);

      List<CreateInventoryItemRequest> items =
          parsedInventoryMapper.toCreateInventoryItemRequestList(parsedItems);

      ParsedInventoryListResponse response = new ParsedInventoryListResponse();
      response.setItems(items);
      response.setTotalItems(items.size());

      log.info("Invoice parsing completed successfully. Extracted {} inventory items", items.size());

      return response;
    } catch (Exception e) {
      log.error("Error parsing invoice image: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error parsing invoice: " + e.getMessage(), e);
    }
  }

  /**
   * Bulk create inventory items with shared vendorId and lotId.
   * 
   * @param bulkRequest the bulk creation request
   * @param userId the user ID
   * @param shopId the shop ID
   * @return bulk creation response with created items
   */
  public BulkCreateInventoryResponse bulkCreate(BulkCreateInventoryRequest bulkRequest, String userId, String shopId) {
    List<InventoryReceiptResponse> createdItems = new ArrayList<>();
    int failedCount = 0;
    
    // Validate vendorId if provided
    if (StringUtils.hasText(bulkRequest.getVendorId())) {
      validateVendorId(bulkRequest.getVendorId(), shopId);
    }
    
    // Determine lotId: use provided lotId or generate new one
    String lotId = determineLotId(bulkRequest.getLotId(), shopId);
    
    log.info("Bulk creating {} inventory items with lotId: {} and vendorId: {}", 
        bulkRequest.getItems() != null ? bulkRequest.getItems().size() : 0, 
        lotId, bulkRequest.getVendorId());
    
    // Process each item
    if (bulkRequest.getItems() != null) {
      for (CreateInventoryItemRequest itemRequest : bulkRequest.getItems()) {
        try {
          // Convert CreateInventoryItemRequest to CreateInventoryRequest
          CreateInventoryRequest fullRequest = convertToCreateInventoryRequest(itemRequest, 
              bulkRequest.getVendorId(), lotId);
          
          InventoryReceiptResponse response = create(fullRequest, userId, shopId);
          createdItems.add(response);
        } catch (Exception e) {
          log.error("Failed to create inventory item: {}", e.getMessage(), e);
          failedCount++;
        }
      }
    }
    
    log.info("Bulk creation completed: {} created, {} failed", createdItems.size(), failedCount);
    
    BulkCreateInventoryResponse bulkResponse = new BulkCreateInventoryResponse();
    bulkResponse.setItems(createdItems);
    bulkResponse.setTotalCreated(createdItems.size());
    bulkResponse.setTotalFailed(failedCount);
    
    return bulkResponse;
  }

  /**
   * Convert CreateInventoryItemRequest to CreateInventoryRequest by adding vendorId and lotId.
   */
  private CreateInventoryRequest convertToCreateInventoryRequest(CreateInventoryItemRequest itemRequest, 
                                                                  String vendorId, String lotId) {
    CreateInventoryRequest fullRequest = new CreateInventoryRequest();
    
    // Copy all fields from item request
    fullRequest.setBarcode(itemRequest.getBarcode());
    fullRequest.setName(itemRequest.getName());
    fullRequest.setDescription(itemRequest.getDescription());
    fullRequest.setCompanyName(itemRequest.getCompanyName());
    fullRequest.setMaximumRetailPrice(itemRequest.getMaximumRetailPrice());
    fullRequest.setCostPrice(itemRequest.getCostPrice());
    fullRequest.setSellingPrice(itemRequest.getSellingPrice());
    fullRequest.setRates(itemRequest.getRates());
    fullRequest.setDefaultRate(itemRequest.getDefaultRate());
    fullRequest.setAdditionalDiscount(itemRequest.getAdditionalDiscount());
    fullRequest.setBusinessType(itemRequest.getBusinessType());
    fullRequest.setLocation(itemRequest.getLocation());
    fullRequest.setCount(itemRequest.getCount());
    fullRequest.setBaseUnit(itemRequest.getBaseUnit());
    fullRequest.setUnitConversions(itemRequest.getUnitConversions());
    fullRequest.setThresholdCount(itemRequest.getThresholdCount());
    fullRequest.setExpiryDate(itemRequest.getExpiryDate());
    fullRequest.setReminderAt(itemRequest.getReminderAt());
    fullRequest.setCustomReminders(itemRequest.getCustomReminders());
    fullRequest.setHsn(itemRequest.getHsn());
    fullRequest.setBatchNo(itemRequest.getBatchNo());
    fullRequest.setSchemeType(itemRequest.getSchemeType());
    fullRequest.setScheme(itemRequest.getScheme());
    fullRequest.setSchemePercentage(itemRequest.getSchemePercentage());
    fullRequest.setSgst(itemRequest.getSgst());
    fullRequest.setCgst(itemRequest.getCgst());
    fullRequest.setItemType(itemRequest.getItemType());
    fullRequest.setItemTypeDegree(itemRequest.getItemTypeDegree());
    fullRequest.setDiscountApplicable(itemRequest.getDiscountApplicable());
    fullRequest.setPurchaseDate(itemRequest.getPurchaseDate());

    // Set shared fields
    fullRequest.setVendorId(vendorId);
    fullRequest.setLotId(lotId);
    
    return fullRequest;
  }

  public InventoryReceiptResponse create(CreateInventoryRequest request, String userId, String shopId) {
    try {
      inventoryValidator.validateCreateRequest(request);
      log.debug("Creating inventory for barcode: {} in shop: {}", request.getBarcode(), shopId);
      if (StringUtils.hasText(request.getVendorId())) {
        validateVendorId(request.getVendorId(), shopId);
      }
      String lotId = determineLotId(request.getLotId(), shopId);

      Inventory inventory = inventoryMapper.toEntity(request);
      inventory.setLotId(lotId);
      inventory.setShopId(shopId);
      inventory.setUserId(userId);
      inventory.setExpiryDate(request.getExpiryDate());
      inventory.setVendorId(request.getVendorId());
      String normalizedBaseUnit = normalizeUnitName(request.getBaseUnit());
      inventory.setBaseUnit(normalizedBaseUnit);
      inventory.setUnitConversions(normalizeUnitConversion(request.getUnitConversions(), normalizedBaseUnit));
      inventory.setPurchaseDate(request.getPurchaseDate() != null ? request.getPurchaseDate() : Instant.now());

      int billQty = request.getCount() != null ? request.getCount() : 0;
      int schemeFreeUnits;
      if (request.getSchemeType() == SchemeType.PERCENTAGE
          && request.getSchemePercentage() != null && request.getSchemePercentage().signum() > 0) {
        BigDecimal pct = request.getSchemePercentage();
        schemeFreeUnits = pct.multiply(BigDecimal.valueOf(billQty))
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).intValue();
      } else {
        schemeFreeUnits = request.getScheme() != null ? request.getScheme() : 0;
      }
      int totalReceivedDisplay = billQty + schemeFreeUnits;
      int totalReceivedBase = toBaseQuantityFromDisplay(totalReceivedDisplay, inventory);
      BigDecimal displayTotalReceived = BigDecimal.valueOf(totalReceivedDisplay).setScale(4, RoundingMode.HALF_UP);
      inventory.setReceivedCount(displayTotalReceived);
      inventory.setCurrentCount(displayTotalReceived);
      inventory.setSoldCount(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
      inventory.setReceivedBaseCount(totalReceivedBase);
      inventory.setCurrentBaseCount(totalReceivedBase);
      inventory.setSoldBaseCount(0);

      inventory = inventoryRepository.save(inventory);

      log.info("Successfully created inventory lot: {} for product: {} in shop: {}",
          inventory.getLotId(), inventory.getBarcode(), shopId);

      CreateReminderForInventoryRequest reminderRequest =
          inventoryMapper.toCreateReminderForInventoryRequest(request, shopId, inventory.getId());
      reminderService.createReminderForInventoryCreate(reminderRequest);
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

  public InventoryListResponse list(String shopId, int page, int size) {
    try {
      // Validate shopId
      inventoryValidator.validateShopId(shopId);

      log.debug("Listing inventory for shop: {}", shopId);
      PageRequest pageable = PageRequest.of(page, size);

      List<Inventory> inventories = inventoryRepository.findByShopId(shopId, pageable);
      List<InventorySummaryDto> summaries = inventories.stream()
          .map(inventoryMapper::toSummary)
          .toList();

      // total count for shop
      long totalItems = inventoryRepository.countByShopId(shopId);
      int totalPages = (int) Math.ceil((double) totalItems / size);

      PageMeta pageMeta = new PageMeta(page, size, totalItems, totalPages);

      return inventoryMapper.toInventoryListResponse(summaries, pageMeta);

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
  public LotListResponse listLots(String shopId, String searchQuery, int page, int size) {
    try {
      // Validate shopId
      inventoryValidator.validateShopId(shopId);

      log.debug("Listing lots for shop: {} with search: {}", shopId, searchQuery);
      PageRequest pageable = PageRequest.of(page, size);
      // Get all inventories for the shop
      List<Inventory> inventories = inventoryRepository.findByShopId(shopId, pageable);

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

      long totalItems = inventoryRepository.countByShopId(shopId);
      int totalPages = (int) Math.ceil((double) totalItems / size);

      PageMeta pageMeta = new PageMeta(page, size, totalItems, totalPages);

      LotListResponse response = new LotListResponse();
      response.setData(summaries);
      response.setMeta(null);
      response.setPage(pageMeta);

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

      List<InventorySummaryDto> items = inventories.stream()
          .map(inventoryMapper::toSummary)
          .toList();

      // Calculate totals
      Integer totalProductCount = inventories.size();
      Integer totalCurrentCount = inventories.stream()
          .mapToInt(this::getCurrentBaseCount)
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
  public LotListResponse searchLots(String shopId, String query, int page, int size) {
    try {
      // Validate inputs
      inventoryValidator.validateShopId(shopId);
      if (query == null || query.trim().isEmpty()) {
        throw new ValidationException("Search query is required");
      }

      log.debug("Searching lots for shop: {} with query: {}", shopId, query);

      return listLots(shopId, query.trim(), page, size);

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


  @Transactional(readOnly = true)
  public InventoryListResponse getLowStockItems(String shopId, int page, int size) {

    inventoryValidator.validateShopId(shopId);

    PageRequest pageable = PageRequest.of(page, size);

    List<Inventory> inventories = inventoryRepository.findByShopId(shopId, pageable);
    List<InventorySummaryDto> lowStock = inventories.stream()
      .filter(inv -> {
        int threshold = inv.getThresholdCount() != null ? inv.getThresholdCount() : 50;
        int current = getCurrentBaseCount(inv);
        return current <= threshold;
      })
      .map(inv -> {
        InventorySummaryDto dto = inventoryMapper.toSummary(inv);
        dto.setThresholdCount(inv.getThresholdCount() != null ? inv.getThresholdCount() : 50);
        return dto;
      })
      .toList();

    long totalItems = inventoryRepository.countByShopId(shopId);
    int totalPages = (int) Math.ceil((double) totalItems / size);

    PageMeta pageMeta = new PageMeta(page, size, totalItems, totalPages);

    return inventoryMapper.toInventoryListResponse(lowStock, pageMeta);
  }

  /**
   * Update inventory by ID.
   * 
   * @param inventoryId the inventory ID (mandatory)
   * @param request the update request containing optional fields
   * @param shopId the shop ID for authorization
   * @return updated inventory detail response
   */
  @Transactional
  public InventoryDetailResponse update(String inventoryId, UpdateInventoryRequest request, String shopId) {
    try {
      // Validate inputs
      inventoryValidator.validateShopId(shopId);
      if (inventoryId == null || inventoryId.trim().isEmpty()) {
        throw new ValidationException("Inventory ID is required");
      }

      log.debug("Updating inventory with ID: {} for shop: {}", inventoryId, shopId);
      inventoryValidator.validateUpdateRequest(request);

      // Find inventory
      Inventory inventory = inventoryRepository.findById(inventoryId)
          .orElseThrow(() -> new ResourceNotFoundException("Inventory", "id", inventoryId));

      // Verify inventory belongs to the shop
      if (!shopId.equals(inventory.getShopId())) {
        throw new ValidationException("Inventory does not belong to the authenticated shop");
      }

      // Update thresholdCount if provided
      if (request.getThresholdCount() != null) {
        inventory.setThresholdCount(request.getThresholdCount());
        log.debug("Updating thresholdCount to {} for inventory: {}", request.getThresholdCount(), inventoryId);
      }

      if (request.getItemType() != null) {
        inventory.setItemType(request.getItemType());
        inventory.setItemTypeDegree(request.getItemTypeDegree());
      }
      if (request.getDiscountApplicable() != null) {
        inventory.setDiscountApplicable(request.getDiscountApplicable());
      }
      if (request.getPurchaseDate() != null) {
        inventory.setPurchaseDate(request.getPurchaseDate());
      }
      if (request.getSchemeType() != null) {
        inventory.setSchemeType(request.getSchemeType());
        inventory.setScheme(request.getScheme());
        inventory.setSchemePercentage(request.getSchemePercentage());
      }
      if (request.getBaseUnit() != null) {
        String normalizedBaseUnit = normalizeUnitName(request.getBaseUnit());
        inventory.setBaseUnit(normalizedBaseUnit);
        UnitConversion sourceConversion = request.getUnitConversions() != null
            ? request.getUnitConversions()
            : inventory.getUnitConversions();
        inventory.setUnitConversions(normalizeUnitConversion(sourceConversion, normalizedBaseUnit));
      } else if (request.getUnitConversions() != null) {
        inventory.setUnitConversions(normalizeUnitConversion(
            request.getUnitConversions(),
            normalizeUnitName(inventory.getBaseUnit())));
      }

      // Update updatedAt timestamp
      inventory.setUpdatedAt(Instant.now());

      // Save inventory
      inventory = inventoryRepository.save(inventory);
      log.info("Successfully updated inventory with ID: {}", inventoryId);

      return inventoryMapper.toDetail(inventory);

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Update inventory validation failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while updating inventory: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error updating inventory");
    } catch (Exception e) {
      log.error("Unexpected error while updating inventory: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }

  private String normalizeUnitName(String unit) {
    if (!StringUtils.hasText(unit)) {
      return "UNIT";
    }
    return unit.trim().toUpperCase();
  }

  private UnitConversion normalizeUnitConversion(UnitConversion rawConversion, String normalizedBaseUnit) {
    if (rawConversion == null) {
      return null;
    }
    if (!StringUtils.hasText(rawConversion.getUnit()) || rawConversion.getFactor() == null) {
      return null;
    }
    String normalizedUnit = normalizeUnitName(rawConversion.getUnit());
    if (normalizedUnit.equals(normalizedBaseUnit)) {
      return null;
    }
    return new UnitConversion(normalizedUnit, rawConversion.getFactor());
  }

  private int getCurrentBaseCount(Inventory inventory) {
    if (inventory.getCurrentBaseCount() != null) {
      return inventory.getCurrentBaseCount();
    }
    if (inventory.getCurrentCount() == null) {
      return 0;
    }
    int factor = 1;
    UnitConversion conversion = inventory.getUnitConversions();
    if (conversion != null && conversion.getFactor() != null && conversion.getFactor() > 0) {
      factor = conversion.getFactor();
    }
    return inventory.getCurrentCount()
        .multiply(BigDecimal.valueOf(factor))
        .setScale(0, RoundingMode.HALF_UP)
        .intValue();
  }

  private int toBaseQuantityFromDisplay(int displayQuantity, Inventory inventory) {
    int factor = 1;
    UnitConversion conversion = inventory.getUnitConversions();
    if (conversion != null && conversion.getFactor() != null && conversion.getFactor() > 0) {
      factor = conversion.getFactor();
    }
    return Math.multiplyExact(displayQuantity, factor);
  }

}
