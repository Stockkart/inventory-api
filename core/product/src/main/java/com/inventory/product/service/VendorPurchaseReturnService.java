package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.domain.model.VendorPurchaseReturnItem;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.product.domain.repository.VendorPurchaseReturnRepository;
import com.inventory.product.rest.dto.request.VendorPurchaseReturnRequest;
import com.inventory.product.util.MongoEmbeddedReadUtil;
import com.inventory.product.rest.dto.response.VendorPurchaseReturnListResponse;
import com.inventory.product.rest.dto.response.VendorPurchaseReturnResponse;
import com.inventory.product.rest.dto.response.VendorPurchaseReturnLineSummaryDto;
import com.inventory.product.rest.dto.response.VendorPurchaseReturnSummaryDto;
import com.inventory.product.validation.CheckoutValidator;
import com.inventory.user.domain.repository.VendorRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Purchase returns against vendor invoices — reduces stock and records inward CN for GSTR-2 CDNR/CDNUR.
 */
@Service
@Slf4j
@Transactional
public class VendorPurchaseReturnService {

  @Autowired
  private VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;

  @Autowired
  private VendorPurchaseReturnRepository vendorPurchaseReturnRepository;

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private PricingRepository pricingRepository;

  @Autowired
  private CheckoutValidator checkoutValidator;

  @Autowired
  private InvoiceSequenceService invoiceSequenceService;

  @Autowired
  private VendorRepository vendorRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  /**
   * Paginated supplier return history for the shop, newest first.
   *
   * @param invoiceNo optional exact purchase invoice number (same as vendor bill no.)
   */
  @Transactional(readOnly = true)
  public VendorPurchaseReturnListResponse listReturns(
      Integer page, Integer limit, String invoiceNo, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");

    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }

    int pageNumber = (page != null && page > 0) ? page - 1 : 0;
    int pageSize = (limit != null && limit > 0) ? limit : 20;
    if (pageSize > 100) {
      pageSize = 100;
    }

    Pageable pageable =
        PageRequest.of(
            pageNumber,
            pageSize,
            Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.DESC, "id")));

    Page<VendorPurchaseReturn> returnPage;

    if (StringUtils.hasText(invoiceNo)) {
      List<VendorPurchaseInvoice> matches =
          vendorPurchaseInvoiceRepository.findByShopIdAndInvoiceNo(shopId, invoiceNo.trim());
      if (matches.isEmpty()) {
        return new VendorPurchaseReturnListResponse(List.of(), pageNumber + 1, pageSize, 0L, 0);
      }
      List<String> invoiceIds =
          matches.stream()
              .map(VendorPurchaseInvoice::getId)
              .filter(StringUtils::hasText)
              .distinct()
              .collect(Collectors.toList());
      returnPage =
          vendorPurchaseReturnRepository.findByShopIdAndVendorPurchaseInvoiceIdIn(
              shopId, invoiceIds, pageable);
    } else {
      returnPage = vendorPurchaseReturnRepository.findByShopId(shopId, pageable);
    }

    List<VendorPurchaseReturnSummaryDto> summaries = toSummaries(returnPage.getContent(), shopId);

    return new VendorPurchaseReturnListResponse(
        summaries,
        pageNumber + 1,
        pageSize,
        returnPage.getTotalElements(),
        returnPage.getTotalPages());
  }

  private List<VendorPurchaseReturnSummaryDto> toSummaries(
      List<VendorPurchaseReturn> rows, String shopId) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }

    Set<String> invIds =
        rows.stream()
            .map(VendorPurchaseReturn::getVendorPurchaseInvoiceId)
            .filter(StringUtils::hasText)
            .collect(Collectors.toSet());

    Map<String, VendorPurchaseInvoice> invoiceById = new HashMap<>();
    for (VendorPurchaseInvoice inv : vendorPurchaseInvoiceRepository.findAllById(invIds)) {
      if (inv != null && StringUtils.hasText(inv.getId()) && shopId.equals(inv.getShopId())) {
        invoiceById.put(inv.getId(), inv);
      }
    }

    Set<String> vendorIds =
        invoiceById.values().stream()
            .map(VendorPurchaseInvoice::getVendorId)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .collect(Collectors.toSet());

    Map<String, String> vendorNameById = new HashMap<>();
    vendorRepository.findAllById(vendorIds).stream()
        .filter(v -> v != null && StringUtils.hasText(v.getId()))
        .forEach(
            v -> {
              String name = v.getName();
              vendorNameById.put(v.getId().trim(), StringUtils.hasText(name) ? name.trim() : null);
            });

    List<String> idsForRawFallback =
        rows.stream()
            .filter(r -> r.getItems() == null || r.getItems().isEmpty())
            .map(VendorPurchaseReturn::getId)
            .filter(StringUtils::hasText)
            .distinct()
            .collect(Collectors.toList());

    Map<String, Document> rawReturnById =
        MongoEmbeddedReadUtil.documentsByShopAndIds(
            mongoTemplate, "vendor_purchase_returns", shopId, idsForRawFallback);

    Set<String> inventoryIdsForLines = new HashSet<>();
    for (VendorPurchaseReturn r : rows) {
      for (VendorPurchaseReturnItem item : resolvedReturnLineItems(r, rawReturnById)) {
        if (StringUtils.hasText(item.getInventoryId())) {
          inventoryIdsForLines.add(item.getInventoryId().trim());
        }
      }
    }

    Map<String, Inventory> inventoryByIdForLines = new HashMap<>();
    if (!inventoryIdsForLines.isEmpty()) {
      for (Inventory invRec : inventoryRepository.findAllById(inventoryIdsForLines)) {
        if (invRec != null && StringUtils.hasText(invRec.getId())) {
          inventoryByIdForLines.put(invRec.getId().trim(), invRec);
        }
      }
    }

    List<VendorPurchaseReturnSummaryDto> out = new ArrayList<>();
    for (VendorPurchaseReturn r : rows) {
      VendorPurchaseInvoice inv = invoiceById.get(r.getVendorPurchaseInvoiceId());
      String vendorName =
          inv != null && StringUtils.hasText(inv.getVendorId())
              ? vendorNameById.get(inv.getVendorId().trim())
              : null;

      List<VendorPurchaseReturnItem> effectiveItems =
          resolvedReturnLineItems(r, rawReturnById);

      Integer persistedLines = r.getTotalLinesReturned();
      int lineCount =
          persistedLines != null ? persistedLines : Math.max(effectiveItems.size(), 0);

      List<VendorPurchaseReturnLineSummaryDto> lineDtos =
          mapReturnLines(inv, effectiveItems, inventoryByIdForLines);

      out.add(
          VendorPurchaseReturnSummaryDto.builder()
              .returnId(r.getId())
              .supplierCreditNoteNo(r.getSupplierCreditNoteNo())
              .vendorPurchaseInvoiceId(r.getVendorPurchaseInvoiceId())
              .invoiceNo(inv != null ? inv.getInvoiceNo() : null)
              .vendorName(vendorName)
              .returnAmount(r.getReturnAmount())
              .totalLinesReturned(lineCount)
              .lines(lineDtos.isEmpty() ? List.of() : lineDtos)
              .reason(r.getReason())
              .createdAt(r.getCreatedAt())
              .build());
    }
    return out;
  }

  /**
   * Prefer mapped entity lines; fall back to raw-document parsing when Mongo nested documents did
   * not hydrate onto {@link VendorPurchaseReturn#getItems()} (legacy keys/shapes still in BSON).
   */
  private List<VendorPurchaseReturnItem> resolvedReturnLineItems(
      VendorPurchaseReturn r, Map<String, Document> rawReturnById) {
    if (r.getItems() != null && !r.getItems().isEmpty()) {
      return r.getItems();
    }
    Document raw = rawReturnById != null ? rawReturnById.get(r.getId()) : null;
    return MongoEmbeddedReadUtil.vendorReturnLineItems(raw);
  }

  private List<VendorPurchaseReturnLineSummaryDto> mapReturnLines(
      VendorPurchaseInvoice inv,
      List<VendorPurchaseReturnItem> items,
      Map<String, Inventory> inventoryByIdForLines) {
    if (items == null || items.isEmpty()) {
      return List.of();
    }
    List<VendorPurchaseReturnLineSummaryDto> list = new ArrayList<>(items.size());
    for (VendorPurchaseReturnItem it : items) {
      String inventoryId =
          StringUtils.hasText(it.getInventoryId()) ? it.getInventoryId().trim() : "";
      Inventory invRec =
          inventoryId.isEmpty() ? null : inventoryByIdForLines.get(inventoryId);
      InvoiceLineExtras extras = invoiceLineExtras(inv, inventoryId);
      String productName =
          invRec != null && StringUtils.hasText(invRec.getName())
              ? invRec.getName().trim()
              : (extras.name != null ? extras.name : null);
      String barcode =
          invRec != null && StringUtils.hasText(invRec.getBarcode())
              ? invRec.getBarcode().trim()
              : extras.barcode;
      Integer baseReturned = it.getBaseQuantityReturned();
      BigDecimal displayQtyReturned = null;
      if (baseReturned != null && baseReturned >= 0) {
        if (invRec != null) {
          displayQtyReturned = toDisplayQuantity(baseReturned, invRec);
        } else {
          displayQtyReturned = BigDecimal.valueOf(baseReturned);
        }
      }
      list.add(
          VendorPurchaseReturnLineSummaryDto.builder()
              .inventoryId(inventoryId.isEmpty() ? null : inventoryId)
              .productName(productName)
              .barcode(barcode)
              .displayQuantityReturned(displayQtyReturned)
              .baseQuantityReturned(it.getBaseQuantityReturned())
              .taxableValue(it.getTaxableValue())
              .centralGstAmount(it.getCentralTaxAmount())
              .stateGstAmount(it.getStateUtTaxAmount())
              .lineNoteValue(it.getLineNoteValue())
              .build());
    }
    return list;
  }

  private static InvoiceLineExtras invoiceLineExtras(
      VendorPurchaseInvoice inv, String inventoryId) {
    if (inv == null || inv.getLines() == null || !StringUtils.hasText(inventoryId)) {
      return InvoiceLineExtras.empty();
    }
    String id = inventoryId.trim();
    for (VendorPurchaseInvoiceLine line : inv.getLines()) {
      if (id.equals(line.getInventoryId())) {
        String name =
            StringUtils.hasText(line.getName()) ? line.getName().trim() : null;
        String barcode =
            StringUtils.hasText(line.getBarcode()) ? line.getBarcode().trim() : null;
        return new InvoiceLineExtras(name, barcode);
      }
    }
    return InvoiceLineExtras.empty();
  }

  private record InvoiceLineExtras(String name, String barcode) {
    static InvoiceLineExtras empty() {
      return new InvoiceLineExtras(null, null);
    }
  }

  public VendorPurchaseReturnResponse processReturn(
      VendorPurchaseReturnRequest request, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    String userId = (String) httpRequest.getAttribute("userId");
    checkoutValidator.validateShopIdAndUserId(shopId, userId);

    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(request.getVendorPurchaseInvoiceId())) {
      throw new ValidationException("vendorPurchaseInvoiceId is required");
    }
    if (request.getItems() == null || request.getItems().isEmpty()) {
      throw new ValidationException("At least one line item with baseQuantityReturned > 0 is required");
    }

    VendorPurchaseInvoice invoice = vendorPurchaseInvoiceRepository
        .findByIdAndShopId(request.getVendorPurchaseInvoiceId(), shopId)
        .orElseThrow(() ->
            new ResourceNotFoundException("VendorPurchaseInvoice", "id",
                "Vendor invoice not found: " + request.getVendorPurchaseInvoiceId()));

    Set<String> allowedInventoryIdsOnInvoice = new HashSet<>();
    if (invoice.getLines() != null) {
      for (VendorPurchaseInvoiceLine line : invoice.getLines()) {
        if (StringUtils.hasText(line.getInventoryId())) {
          allowedInventoryIdsOnInvoice.add(line.getInventoryId());
        }
      }
    }

    Set<String> seenIds = new HashSet<>();
    for (VendorPurchaseReturnRequest.Item it : request.getItems()) {
      if (!StringUtils.hasText(it.getInventoryId())) {
        throw new ValidationException("inventoryId is required on every item");
      }
      if (it.getBaseQuantityReturned() == null || it.getBaseQuantityReturned() <= 0) {
        throw new ValidationException("baseQuantityReturned must be > 0 for inventory " + it.getInventoryId());
      }
      if (!seenIds.add(it.getInventoryId())) {
        throw new ValidationException("Duplicate inventoryId in items: " + it.getInventoryId());
      }
    }

    String supplierCn = invoiceSequenceService.getNextVendorCreditNoteNo(shopId);

    LinkedHashMap<String, VendorPurchaseReturnItem> aggregates = new LinkedHashMap<>();

    BigDecimal totalReturnAmount = BigDecimal.ZERO;

    try {
      for (VendorPurchaseReturnRequest.Item payload : request.getItems()) {
        if (!allowedInventoryIdsOnInvoice.contains(payload.getInventoryId())) {
          throw new ValidationException(
              "Inventory " + payload.getInventoryId()
                  + " is not linked to this vendor invoice.");
        }

        Inventory inventory =
            inventoryRepository.findById(payload.getInventoryId())
                .orElseThrow(
                    () ->
                        new ResourceNotFoundException(
                            "Inventory", "id", "Inventory not found: " + payload.getInventoryId()));

        if (!shopId.equals(inventory.getShopId())) {
          throw new ValidationException("Inventory does not belong to this shop");
        }
        if (!StringUtils.hasText(inventory.getVendorPurchaseInvoiceId())
            || !invoice.getId().equals(inventory.getVendorPurchaseInvoiceId())) {
          throw new ValidationException(
              "Inventory is not booked against invoice " + invoice.getId());
        }

        int qtyBase = payload.getBaseQuantityReturned();
        int maxReturnable = getMaxReturnableBaseUnitsForVendorReturn(inventory);
        if (qtyBase > maxReturnable) {
          throw new ValidationException(
              "Insufficient stock on hand to return. At most "
                  + maxReturnable
                  + " base units may be returned (from current billed stock); inventory "
                  + payload.getInventoryId());
        }

        Pricing pricing =
            StringUtils.hasText(inventory.getPricingId())
                ? pricingRepository.findById(inventory.getPricingId()).orElse(null)
                : null;
        BigDecimal costPrice =
            pricing != null && pricing.getCostPrice() != null
                ? pricing.getCostPrice()
                : BigDecimal.ZERO;
        String sgstStr =
            pricing != null && StringUtils.hasText(pricing.getSgst()) ? pricing.getSgst() : "0";
        String cgstStr =
            pricing != null && StringUtils.hasText(pricing.getCgst()) ? pricing.getCgst() : "0";
        BigDecimal sgstRate = parseRatePct(sgstStr);
        BigDecimal cgstRate = parseRatePct(cgstStr);

        // Cost on pricing / vendor bill is per display (invoice) unit, not per base unit.
        // Match purchase valuation: taxable = unitCost × quantity returned in those same units.
        BigDecimal displayQtyReturned = toDisplayQuantity(qtyBase, inventory);
        BigDecimal taxableVal =
            costPrice.multiply(displayQtyReturned).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cgstAmt =
            taxableVal
                .multiply(cgstRate)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal sgstAmt =
            taxableVal
                .multiply(sgstRate)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal lineTotal = taxableVal.add(cgstAmt).add(sgstAmt).setScale(2, RoundingMode.HALF_UP);

        reduceInventoryForVendorReturn(inventory, qtyBase, shopId);

        VendorPurchaseReturnItem lineItem = new VendorPurchaseReturnItem();
        lineItem.setInventoryId(payload.getInventoryId());
        lineItem.setBaseQuantityReturned(qtyBase);
        lineItem.setTaxableValue(taxableVal);
        lineItem.setCentralTaxAmount(cgstAmt);
        lineItem.setStateUtTaxAmount(sgstAmt);
        lineItem.setLineNoteValue(lineTotal);
        aggregates.put(payload.getInventoryId(), lineItem);
        totalReturnAmount = totalReturnAmount.add(lineTotal);
      }

      VendorPurchaseReturn record = new VendorPurchaseReturn();
      record.setShopId(shopId);
      record.setUserId(userId);
      record.setVendorPurchaseInvoiceId(invoice.getId());
      record.setSupplierCreditNoteNo(supplierCn);
      record.setItems(List.copyOf(aggregates.values()));
      record.setReturnAmount(totalReturnAmount.setScale(2, RoundingMode.HALF_UP));
      record.setTotalLinesReturned(aggregates.size());
      record.setReason(StringUtils.hasText(request.getReason()) ? request.getReason().trim() : null);
      record.setCreatedAt(Instant.now());
      record.setUpdatedAt(Instant.now());
      record = vendorPurchaseReturnRepository.save(record);

      log.info(
          "Vendor purchase return recorded: {}, supplierCn={}, invoice={}, shop={}",
          record.getId(),
          supplierCn,
          invoice.getId(),
          shopId);

      return new VendorPurchaseReturnResponse(
          record.getId(),
          supplierCn,
          invoice.getId(),
          record.getReturnAmount(),
          record.getTotalLinesReturned(),
          record.getCreatedAt());
    } catch (ValidationException | ResourceNotFoundException e) {
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error vendor purchase return shop={}", shopId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Error recording vendor purchase return: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Unexpected vendor purchase return error shop={}", shopId, e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
          "Unexpected error recording vendor purchase return: " + e.getMessage(), e);
    }
  }

  private void reduceInventoryForVendorReturn(
      Inventory inventory, int baseQuantity, String shopId) {
    if (!shopId.equals(inventory.getShopId())) {
      throw new ValidationException("Inventory does not belong to shop " + shopId);
    }

    BigDecimal displayQty = toDisplayQuantity(baseQuantity, inventory);
    int maxReturnable = getMaxReturnableBaseUnitsForVendorReturn(inventory);
    if (baseQuantity > maxReturnable) {
      throw new ValidationException(
          "Insufficient stock base units (max "
              + maxReturnable
              + ") for inventory "
              + inventory.getId());
    }

    int currentBase = getCurrentBaseCount(inventory);
    if (currentBase < baseQuantity) {
      throw new ValidationException(
          "Insufficient stock base units "
              + currentBase
              + " for inventory "
              + inventory.getId());
    }

    BigDecimal curDisp = getCurrentDisplayCount(inventory);
    inventory.setCurrentCount(
        curDisp.subtract(displayQty).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP));
    inventory.setCurrentBaseCount(currentBase - baseQuantity);
    inventoryRepository.save(inventory);
  }

  private int getCurrentBaseCount(Inventory inventory) {
    if (inventory.getCurrentBaseCount() != null) {
      return inventory.getCurrentBaseCount();
    }
    if (inventory.getCurrentCount() == null) {
      return 0;
    }
    int factor = getDisplayToBaseFactor(inventory);
    return inventory
        .getCurrentCount()
        .multiply(BigDecimal.valueOf(factor))
        .setScale(0, RoundingMode.HALF_UP)
        .intValue();
  }

  /**
   * Cap for supplier returns (base units): when both billed stock ({@code currentCount} × pack
   * factor) and {@code currentBaseCount} are present, use the smaller so returns match sellable
   * quantity on the invoice lot.
   */
  private int getMaxReturnableBaseUnitsForVendorReturn(Inventory inventory) {
    Integer storedRaw = inventory.getCurrentBaseCount();
    int storedOk = storedRaw != null && storedRaw >= 0 ? storedRaw : -1;

    BigDecimal cnt = inventory.getCurrentCount();
    int factor = getDisplayToBaseFactor(inventory);

    Integer fromSell = null;
    if (cnt != null) {
      fromSell =
          cnt.multiply(BigDecimal.valueOf(factor))
              .setScale(0, RoundingMode.HALF_UP)
              .max(BigDecimal.ZERO)
              .intValue();
    }

    if (fromSell != null && storedOk >= 0) {
      return Math.min(fromSell, storedOk);
    }
    if (fromSell != null) {
      return fromSell;
    }
    if (storedOk >= 0) {
      return storedOk;
    }
    return getCurrentBaseCount(inventory);
  }

  private BigDecimal getCurrentDisplayCount(Inventory inventory) {
    if (inventory.getCurrentCount() != null) {
      return inventory.getCurrentCount();
    }
    return toDisplayQuantity(Math.max(getCurrentBaseCount(inventory), 0), inventory);
  }

  private BigDecimal toDisplayQuantity(int baseQuantity, Inventory inventory) {
    int factor = getDisplayToBaseFactor(inventory);
    if (factor <= 0) {
      return BigDecimal.valueOf(baseQuantity);
    }
    return BigDecimal.valueOf(baseQuantity)
        .divide(BigDecimal.valueOf(factor), 4, RoundingMode.HALF_UP);
  }

  private int getDisplayToBaseFactor(Inventory inventory) {
    UnitConversion c = inventory.getUnitConversions();
    if (c == null || c.getFactor() == null || c.getFactor() <= 0) {
      return 1;
    }
    return c.getFactor();
  }

  private BigDecimal parseRatePct(String s) {
    if (!StringUtils.hasText(s)) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(s.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }
}
