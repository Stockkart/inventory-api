package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.accounting.service.PurchaseJournalService;
import com.inventory.accounting.service.VendorPurchaseLedgerInput;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import com.inventory.product.rest.dto.request.VendorPurchaseInvoiceRequest;
import com.inventory.reminders.rest.dto.request.CreateReminderForInventoryRequest;
import com.inventory.reminders.service.ReminderService;
import com.inventory.ocr.service.InvoiceParserService;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.rest.dto.request.BulkCreateInventoryRequest;
import com.inventory.product.rest.dto.request.CreateInventoryItemRequest;
import com.inventory.product.rest.dto.request.CreateInventoryRequest;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import com.inventory.product.rest.dto.response.BulkCreateInventoryResponse;
import com.inventory.product.rest.dto.response.PostPurchaseLedgerResultDto;
import com.inventory.product.rest.dto.response.InventoryDetailResponse;
import com.inventory.product.rest.dto.response.InventoryListResponse;
import com.inventory.product.rest.dto.response.InventoryReceiptResponse;
import com.inventory.product.rest.dto.response.InventorySummaryDto;
import com.inventory.product.rest.dto.response.LotDetailDto;
import com.inventory.product.rest.dto.response.LotListResponse;
import com.inventory.product.rest.dto.response.LotSummaryDto;
import com.inventory.product.rest.dto.response.PageMeta;
import com.inventory.product.rest.dto.response.ParsedInventoryListResponse;
import java.util.ArrayList;
import com.inventory.product.mapper.InventoryMapper;
import com.inventory.product.migration.ExcelStockParser;
import com.inventory.product.mapper.ParsedInventoryMapper;
import com.inventory.product.utils.constants.ProductMetricsConstants;
import com.inventory.product.validation.InventoryValidator;
import com.inventory.product.validation.StockSheetValidator;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class InventoryService {

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;

  @Autowired
  private VendorRepository vendorRepository;

  @Autowired
  private InventoryMapper inventoryMapper;

  @Autowired
  private InventoryValidator inventoryValidator;

  @Autowired
  private ReminderService reminderService;

  @Autowired
  private com.inventory.user.domain.repository.ShopVendorRepository shopVendorRepository;

  @Autowired
  private InvoiceParserService invoiceParserService;

  @Autowired
  private ParsedInventoryMapper parsedInventoryMapper;

  @Autowired
  private com.inventory.product.validation.ImageValidator imageValidator;

  @Autowired
  private StockSheetValidator stockSheetValidator;

  @Autowired
  private ExcelStockParser excelStockParser;

  @Autowired(required = false)
  private com.inventory.metrics.MetricsWrapper metrics;

  @Autowired
  private PurchaseJournalService purchaseJournalService;

  @Autowired(required = false)
  private com.inventory.credit.service.CreditService creditService;

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

    imageValidator.validateImageFileForParsing(image);

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
    imageValidator.validateImageBytes(imageBytes);

    try {
      List<com.inventory.ocr.dto.ParsedInventoryItem> parsedItems =
          invoiceParserService.parseInvoiceImage(imageBytes);

      List<CreateInventoryItemRequest> items =
          parsedInventoryMapper.toCreateInventoryItemRequestList(parsedItems);

      log.info("Invoice parsing completed successfully. Extracted {} inventory items", items.size());
      return parsedInventoryMapper.toParsedInventoryListResponse(items);
    } catch (Exception e) {
      log.error("Error parsing invoice image: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error parsing invoice: " + e.getMessage(), e);
    }
  }

  /**
   * Parse Excel stock sheet and extract inventory items for migration.
   * Supports .xls and .xlsx from various legacy apps with auto-detected column mapping.
   *
   * @param file the Excel file to parse
   * @return ParsedInventoryListResponse containing list of CreateInventoryItemRequest items
   */
  @Transactional(readOnly = true)
  public ParsedInventoryListResponse parseStockSheet(MultipartFile file) {
    log.info("Processing stock sheet parse request for file: {}, size: {} bytes",
        file.getOriginalFilename(), file.getSize());

    stockSheetValidator.validateStockSheet(file);

    try {
      List<com.inventory.ocr.dto.ParsedInventoryItem> parsedItems =
          excelStockParser.parse(file.getInputStream(), file.getOriginalFilename());

      List<CreateInventoryItemRequest> items =
          parsedInventoryMapper.toCreateInventoryItemRequestList(parsedItems);

      log.info("Stock sheet parsing completed. Extracted {} inventory items", items.size());
      return parsedInventoryMapper.toParsedInventoryListResponse(items);
    } catch (IOException e) {
      log.error("Error reading Excel file: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error reading Excel file: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Error parsing stock sheet: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error parsing stock sheet: " + e.getMessage(), e);
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

    if (!StringUtils.hasText(bulkRequest.getVendorId())) {
      throw new ValidationException("Vendor is required for bulk inventory registration");
    }
    validateVendorId(bulkRequest.getVendorId(), shopId);

    VendorPurchaseInvoiceRequest invReq = bulkRequest.getVendorPurchaseInvoice();
    boolean userInvoice =
        invReq != null && StringUtils.hasText(invReq.getInvoiceNo());

    VendorPurchaseInvoice pendingInvoice = new VendorPurchaseInvoice();
    pendingInvoice.setShopId(shopId);
    pendingInvoice.setVendorId(bulkRequest.getVendorId());
    pendingInvoice.setCreatedAt(Instant.now());
    pendingInvoice.setCreatedByUserId(userId);
    pendingInvoice.setLines(new ArrayList<>());

    if (userInvoice) {
      String normalizedNo = invReq.getInvoiceNo().trim();
      if (vendorPurchaseInvoiceRepository.existsByShopIdAndVendorIdAndInvoiceNo(
          shopId, bulkRequest.getVendorId(), normalizedNo)) {
        throw new ValidationException(
            "An invoice with this number already exists for this vendor");
      }
      pendingInvoice.setInvoiceNo(normalizedNo);
      pendingInvoice.setSynthetic(Boolean.FALSE);
    } else {
      pendingInvoice.setInvoiceNo("AUTO-" + new ObjectId().toHexString());
      pendingInvoice.setSynthetic(Boolean.TRUE);
    }
    if (invReq != null) {
      pendingInvoice.setInvoiceDate(invReq.getInvoiceDate());
      pendingInvoice.setLineSubTotal(invReq.getLineSubTotal());
      pendingInvoice.setTaxTotal(invReq.getTaxTotal());
      pendingInvoice.setShippingCharge(invReq.getShippingCharge());
      pendingInvoice.setOtherCharges(invReq.getOtherCharges());
      pendingInvoice.setRoundOff(invReq.getRoundOff());
      pendingInvoice.setInvoiceTotal(invReq.getInvoiceTotal());
      pendingInvoice.setPaymentMethod(invReq.getPaymentMethod());
      pendingInvoice.setPaidAmount(invReq.getPaidAmount());
    }

    try {
      pendingInvoice = vendorPurchaseInvoiceRepository.save(pendingInvoice);
    } catch (DuplicateKeyException e) {
      throw new ValidationException(
          "An invoice with this number already exists for this vendor");
    }

    String registrationId = pendingInvoice.getId();

    log.info(
        "Bulk creating {} inventory items with vendorPurchaseInvoiceId: {} and vendorId: {}",
        bulkRequest.getItems() != null ? bulkRequest.getItems().size() : 0,
        registrationId,
        bulkRequest.getVendorId());

    List<VendorPurchaseInvoiceLine> invoiceLines = new ArrayList<>();
    if (bulkRequest.getItems() != null) {
      for (CreateInventoryItemRequest itemRequest : bulkRequest.getItems()) {
        try {
          CreateInventoryRequest fullRequest =
              inventoryMapper.toCreateInventoryRequest(
                  itemRequest, bulkRequest.getVendorId(), registrationId);
          fullRequest.setVendorPurchaseInvoiceId(registrationId);

          InventoryReceiptResponse response = create(fullRequest, userId, shopId);
          createdItems.add(response);

          VendorPurchaseInvoiceLine line = new VendorPurchaseInvoiceLine();
          line.setLineIndex(invoiceLines.size());
          line.setName(itemRequest.getName());
          line.setBarcode(itemRequest.getBarcode());
          line.setCount(itemRequest.getCount());
          line.setCostPrice(itemRequest.getCostPrice());
          line.setPriceToRetail(itemRequest.getPriceToRetail());
          line.setInventoryId(response.getId());
          invoiceLines.add(line);
        } catch (Exception e) {
          log.error("Failed to create inventory item: {}", e.getMessage(), e);
          failedCount++;
        }
      }
    }

    String returnedInvoiceId = null;
    if (invoiceLines.isEmpty()) {
      vendorPurchaseInvoiceRepository.deleteById(pendingInvoice.getId());
    } else {
      pendingInvoice.setLines(invoiceLines);
      vendorPurchaseInvoiceRepository.save(pendingInvoice);
      returnedInvoiceId = pendingInvoice.getId();
    }

    log.info("Bulk creation completed: {} created, {} failed", createdItems.size(), failedCount);

    String accountingJournalEntryId = null;
    String creditEntryId = null;
    if (returnedInvoiceId != null) {
      var invOpt = vendorPurchaseInvoiceRepository.findById(returnedInvoiceId);
      if (invOpt.isPresent()) {
        VendorPurchaseInvoice persistedInvoice = invOpt.get();
        VendorPurchaseLedgerInput ledgerIn = toVendorPurchaseLedgerInput(invOpt.get());
        try {
          accountingJournalEntryId =
              purchaseJournalService
                  .recordVendorPurchaseLedger(shopId, userId, ledgerIn)
                  .orElse(null);
        } catch (ValidationException vex) {
          log.warn(
              "Accounting PURCHASE journal rejected for vendor invoice {} shop {} — stock-in kept: {}",
              returnedInvoiceId,
              shopId,
              vex.getMessage());
        } catch (Exception ex) {
          log.error(
              "Accounting PURCHASE journal failed for vendor invoice {} shop {} — stock-in kept: {}",
              returnedInvoiceId,
              shopId,
              ex.getMessage(),
              ex);
        }
        try {
          creditEntryId = postCreditChargeForVendorInvoice(persistedInvoice, shopId, userId);
        } catch (ValidationException vex) {
          throw vex;
        } catch (Exception ex) {
          log.error(
              "Credit charge posting failed for vendor invoice {} shop {}: {}",
              returnedInvoiceId,
              shopId,
              ex.getMessage(),
              ex);
        }
      }
    }

    if (metrics != null && !createdItems.isEmpty()) {
      metrics.record(
          ProductMetricsConstants.INVENTORY_OPERATION,
          1,
          "module",
          ProductMetricsConstants.MODULE,
          "operation",
          "bulk_create");
      metrics.record(
          ProductMetricsConstants.INVENTORY_ITEMS_ADDED,
          createdItems.size(),
          "module",
          ProductMetricsConstants.MODULE);
    }

    BulkCreateInventoryResponse out =
        inventoryMapper.toBulkCreateInventoryResponse(
            createdItems, failedCount, returnedInvoiceId, accountingJournalEntryId);
    out.setCreditEntryId(creditEntryId);
    return out;
  }

  /**
   * Idempotently ensures a PURCHASE journal exists for a saved vendor stock-in invoice (same rules
   * as bulk registration). Useful when postings were skipped on an older server build.
   */
  public PostPurchaseLedgerResultDto syncPurchaseLedgerForVendorInvoice(
      String invoiceId, String shopId, String userId) {
    VendorPurchaseInvoice inv =
        vendorPurchaseInvoiceRepository
            .findByIdAndShopId(invoiceId, shopId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Vendor purchase invoice", "id", invoiceId));

    VendorPurchaseLedgerInput ledgerIn = toVendorPurchaseLedgerInput(inv);
    try {
      Optional<String> jid =
          purchaseJournalService.recordVendorPurchaseLedger(shopId, userId, ledgerIn);
      if (jid.isEmpty()) {
        return new PostPurchaseLedgerResultDto(
            null,
            true,
            "No ledger entry was created — the payable amount was zero "
                + "(invoice totals missing or qty × unit cost/PTR yielded no base). "
                + "Fill invoice totals or line amounts and try again.");
      }
      return new PostPurchaseLedgerResultDto(jid.get(), false, null);
    } catch (ValidationException vex) {
      throw vex;
    } catch (Exception ex) {
      log.error(
          "Purchase ledger sync failed for vendor invoice {} shop {}: {}",
          invoiceId,
          shopId,
          ex.getMessage(),
          ex);
      throw new BaseException(
          ErrorCode.INTERNAL_SERVER_ERROR,
          "Purchase ledger sync failed: " + ex.getMessage(),
          ex);
    }
  }

  private VendorPurchaseLedgerInput toVendorPurchaseLedgerInput(VendorPurchaseInvoice inv) {
    List<VendorPurchaseLedgerInput.Line> lines =
        inv.getLines().stream()
            .map(
                l ->
                    new VendorPurchaseLedgerInput.Line(
                        l.getCount(), l.getCostPrice(), l.getPriceToRetail()))
            .toList();
    String vendorDisplayName =
        StringUtils.hasText(inv.getVendorId())
            ? vendorRepository
                .findById(inv.getVendorId().trim())
                .map(Vendor::getName)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .orElse(null)
            : null;
    return new VendorPurchaseLedgerInput(
        inv.getId(),
        inv.getVendorId(),
        inv.getInvoiceNo(),
        inv.getInvoiceDate(),
        inv.getCreatedAt(),
        inv.getLineSubTotal(),
        inv.getTaxTotal(),
        inv.getShippingCharge(),
        inv.getOtherCharges(),
        inv.getRoundOff(),
        inv.getInvoiceTotal(),
        lines,
        vendorDisplayName);
  }

  private String postCreditChargeForVendorInvoice(
      VendorPurchaseInvoice inv, String shopId, String userId) {
    if (creditService == null || inv == null) {
      return null;
    }
    BigDecimal total = deriveInvoiceTotalForCredit(inv);
    if (total.signum() <= 0) {
      return null;
    }
    String method = normalizePaymentMethod(inv.getPaymentMethod());
    BigDecimal paidNow = resolveVendorPaidNow(total, method, inv.getPaidAmount());
    if (paidNow.compareTo(total) > 0) {
      throw new ValidationException("Paid amount cannot exceed vendor invoice total");
    }
    BigDecimal outstanding = total.subtract(paidNow).setScale(4, RoundingMode.HALF_UP);
    if (outstanding.signum() <= 0) {
      return null;
    }

    String vendorId = StringUtils.hasText(inv.getVendorId()) ? inv.getVendorId().trim() : null;
    if (!StringUtils.hasText(vendorId)) {
      throw new ValidationException("Vendor id is required to track vendor credit");
    }
    String vendorName =
        vendorRepository
            .findById(vendorId)
            .map(Vendor::getName)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .orElse("Vendor " + vendorId);

    com.inventory.credit.rest.dto.request.CreateCreditChargeRequest charge =
        new com.inventory.credit.rest.dto.request.CreateCreditChargeRequest();
    charge.setPartyType(com.inventory.credit.domain.model.CreditPartyType.VENDOR);
    charge.setPartyId(vendorId);
    charge.setPartyDisplayName(vendorName);
    charge.setAmount(outstanding);
    charge.setReferenceType("PURCHASE");
    charge.setReferenceId(inv.getId());
    charge.setSourceKey("PURCHASE:CREDIT:" + inv.getId());
    charge.setNote(
        "Vendor payable"
            + (StringUtils.hasText(inv.getInvoiceNo()) ? " · Inv " + inv.getInvoiceNo().trim() : ""));
    var entry = creditService.createCharge(shopId, userId, charge);
    return entry != null ? entry.getId() : null;
  }

  private static BigDecimal deriveInvoiceTotalForCredit(VendorPurchaseInvoice inv) {
    BigDecimal invTotal = nz(inv.getInvoiceTotal());
    if (invTotal.signum() > 0) {
      return invTotal.setScale(4, RoundingMode.HALF_UP);
    }
    return nz(inv.getLineSubTotal())
        .add(nz(inv.getTaxTotal()))
        .add(nz(inv.getShippingCharge()))
        .add(nz(inv.getOtherCharges()))
        .add(nz(inv.getRoundOff()))
        .setScale(4, RoundingMode.HALF_UP);
  }

  private static String normalizePaymentMethod(String raw) {
    if (!StringUtils.hasText(raw)) {
      return "CASH";
    }
    return raw.trim().toUpperCase();
  }

  private static BigDecimal resolveVendorPaidNow(
      BigDecimal total, String paymentMethod, BigDecimal paidAmount) {
    if ("CREDIT".equals(paymentMethod)) {
      return nz(paidAmount).setScale(4, RoundingMode.HALF_UP);
    }
    if (paidAmount != null && paidAmount.signum() >= 0) {
      return paidAmount.setScale(4, RoundingMode.HALF_UP);
    }
    return total;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
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
      inventory.setBillingMode(normalizeBillingMode(request.getBillingMode()));

      int billQty = request.getCount() != null ? request.getCount() : 0;
      boolean useNewFixedUnits = request.getSchemePayFor() != null || request.getSchemeFree() != null;
      int schemeFreeUnits;
      if (request.getSchemeType() == SchemeType.PERCENTAGE
          && request.getSchemePercentage() != null && request.getSchemePercentage().signum() > 0) {
        BigDecimal pct = request.getSchemePercentage();
        schemeFreeUnits = pct.multiply(BigDecimal.valueOf(billQty))
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).intValue();
      } else if (useNewFixedUnits) {
        // New: quantity = count only; scheme is payFor + free (e.g. 10 + 2), not added to stock
        schemeFreeUnits = 0;
      } else {
        // Legacy: scheme = free units, total = count + scheme
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
      if (useNewFixedUnits) {
        inventory.setSchemePayFor(request.getSchemePayFor() != null ? request.getSchemePayFor() : 0);
        inventory.setSchemeFree(request.getSchemeFree() != null ? request.getSchemeFree() : 0);
        inventory.setScheme(null);
      } else if (request.getScheme() != null) {
        inventory.setScheme(request.getScheme());
      }

      inventory = inventoryRepository.save(inventory);

      log.info("Successfully created inventory lot: {} for product: {} in shop: {}",
          inventory.getLotId(), inventory.getBarcode(), shopId);

      if (metrics != null) {
        int itemCount = request.getCount() != null ? request.getCount() : 1;
        metrics.record(ProductMetricsConstants.INVENTORY_OPERATION, 1, "module", ProductMetricsConstants.MODULE, "operation", "create");
        metrics.record(ProductMetricsConstants.INVENTORY_ITEMS_ADDED, itemCount, "module", ProductMetricsConstants.MODULE);
      }

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

  @Transactional(readOnly = true)
  public List<InventoryDetailResponse> getByIds(List<String> inventoryIds, String shopId) {
    inventoryValidator.validateShopId(shopId);
    if (inventoryIds == null || inventoryIds.isEmpty()) {
      return List.of();
    }

    List<String> normalizedIds = inventoryIds.stream()
        .filter(StringUtils::hasText)
        .map(String::trim)
        .distinct()
        .toList();
    if (normalizedIds.isEmpty()) {
      return List.of();
    }

    List<Inventory> inventories = inventoryRepository.findByIdIn(normalizedIds);
    Map<String, Inventory> inventoryById = inventories.stream()
        .filter(inv -> shopId.equals(inv.getShopId()))
        .collect(Collectors.toMap(Inventory::getId, inv -> inv, (a, b) -> a));

    List<InventoryDetailResponse> out = new ArrayList<>();
    for (String id : normalizedIds) {
      Inventory inv = inventoryById.get(id);
      if (inv != null) {
        out.add(inventoryMapper.toDetail(inv));
      }
    }
    return out;
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
      return inventoryMapper.toLotListResponse(summaries, pageMeta);

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

      // Product details - only update when provided
      if (request.getBarcode() != null) inventory.setBarcode(request.getBarcode());
      if (request.getName() != null) inventory.setName(request.getName());
      if (request.getDescription() != null) inventory.setDescription(request.getDescription());
      if (request.getCompanyName() != null) inventory.setCompanyName(request.getCompanyName());
      if (request.getBusinessType() != null) inventory.setBusinessType(request.getBusinessType());
      if (request.getLocation() != null) inventory.setLocation(request.getLocation());

      // Inventory attributes
      if (request.getExpiryDate() != null) inventory.setExpiryDate(request.getExpiryDate());
      if (request.getHsn() != null) inventory.setHsn(request.getHsn());
      if (request.getBatchNo() != null) inventory.setBatchNo(request.getBatchNo());
      if (request.getVendorId() != null) inventory.setVendorId(request.getVendorId());
      if (request.getThresholdCount() != null) inventory.setThresholdCount(request.getThresholdCount());
      if (request.getBillingMode() != null) inventory.setBillingMode(normalizeBillingMode(request.getBillingMode()));

      if (request.getItemType() != null) {
        inventory.setItemType(request.getItemType());
        inventory.setItemTypeDegree(request.getItemTypeDegree());
      }
      if (request.getDiscountApplicable() != null) inventory.setDiscountApplicable(request.getDiscountApplicable());
      if (request.getPurchaseDate() != null) inventory.setPurchaseDate(request.getPurchaseDate());

      if (request.getSchemeType() != null) {
        inventory.setSchemeType(request.getSchemeType());
        inventory.setSchemePercentage(request.getSchemePercentage());
        if (request.getSchemePayFor() != null || request.getSchemeFree() != null) {
          inventory.setSchemePayFor(request.getSchemePayFor() != null ? request.getSchemePayFor() : 0);
          inventory.setSchemeFree(request.getSchemeFree() != null ? request.getSchemeFree() : 0);
          inventory.setScheme(null);
        } else {
          inventory.setScheme(request.getScheme());
        }
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

      if (metrics != null) {
        metrics.record(ProductMetricsConstants.INVENTORY_OPERATION, 1, "module", ProductMetricsConstants.MODULE, "operation", "update");
        metrics.record(ProductMetricsConstants.INVENTORY_ITEMS_ADDED, 1, "module", ProductMetricsConstants.MODULE);
      }

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

  private BillingMode normalizeBillingMode(BillingMode billingMode) {
    return billingMode != null ? billingMode : BillingMode.REGULAR;
  }

}
