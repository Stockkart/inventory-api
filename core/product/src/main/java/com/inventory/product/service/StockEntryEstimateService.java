package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.StockEntryEstimate;
import com.inventory.product.domain.model.StockEntryEstimateLine;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.StockEntryEstimateState;
import com.inventory.product.domain.repository.StockEntryEstimateRepository;
import com.inventory.product.rest.dto.request.BulkCreateInventoryRequest;
import com.inventory.product.rest.dto.request.CreateInventoryItemRequest;
import com.inventory.product.rest.dto.request.UpsertStockEntryEstimateRequest;
import com.inventory.product.rest.dto.request.VendorPurchaseInvoiceRequest;
import com.inventory.product.rest.dto.response.BulkCreateInventoryResponse;
import com.inventory.product.rest.dto.response.InventoryReceiptResponse;
import com.inventory.product.rest.dto.response.StockEntryEstimateListResponse;
import com.inventory.product.rest.dto.response.StockEntryEstimateResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@RequiredArgsConstructor
public class StockEntryEstimateService {

  private static final int DEFAULT_LIST_SIZE = 20;
  private static final int MAX_LIST_SIZE = 100;

  private final StockEntryEstimateRepository stockEntryEstimateRepository;
  private final InvoiceSequenceService invoiceSequenceService;
  private final InventoryService inventoryService;

  @Transactional(readOnly = true)
  public StockEntryEstimateListResponse list(
      String shopId, StockEntryEstimateState stateFilter, Integer page, Integer size) {
    int pageIdx = page != null && page >= 0 ? page : 0;
    int pageSize =
        size == null ? DEFAULT_LIST_SIZE : Math.min(Math.max(size, 1), MAX_LIST_SIZE);
    var pageable =
        PageRequest.of(pageIdx, pageSize, Sort.by(Sort.Direction.DESC, "updatedAt"));
    Page<StockEntryEstimate> result =
        stateFilter != null
            ? stockEntryEstimateRepository.findByShopIdAndState(shopId, stateFilter, pageable)
            : stockEntryEstimateRepository.findByShopIdAndStateNot(
                shopId, StockEntryEstimateState.DISCARDED, pageable);
    List<StockEntryEstimateListResponse.StockEntryEstimateSummary> summaries =
        result.getContent().stream().map(this::toSummary).toList();
    return new StockEntryEstimateListResponse(
        summaries,
        result.getTotalElements(),
        result.getNumber(),
        result.getSize(),
        result.getTotalPages());
  }

  @Transactional(readOnly = true)
  public StockEntryEstimateResponse get(String id, String shopId) {
    return toResponse(load(id, shopId));
  }

  @Transactional
  public StockEntryEstimateResponse create(
      UpsertStockEntryEstimateRequest request, String userId, String shopId) {
    Instant now = Instant.now();
    StockEntryEstimate draft = new StockEntryEstimate();
    draft.setShopId(shopId);
    draft.setUserId(userId);
    draft.setEstimateNo(invoiceSequenceService.getNextStockEntryEstimateNo(shopId));
    draft.setState(StockEntryEstimateState.OPEN);
    applyUpsert(draft, request);
    draft.setCreatedAt(now);
    draft.setUpdatedAt(now);
    draft = stockEntryEstimateRepository.save(draft);
    log.info("Created stock-entry estimate {} for shop {}", draft.getId(), shopId);
    return toResponse(draft);
  }

  @Transactional
  public StockEntryEstimateResponse update(
      String id, UpsertStockEntryEstimateRequest request, String userId, String shopId) {
    StockEntryEstimate draft = load(id, shopId);
    assertEditable(draft);
    applyUpsert(draft, request);
    draft.setUpdatedAt(Instant.now());
    draft = stockEntryEstimateRepository.save(draft);
    return toResponse(draft);
  }

  @Transactional
  public void discard(String id, String shopId) {
    StockEntryEstimate draft = load(id, shopId);
    if (draft.getState() == StockEntryEstimateState.CONVERTED) {
      throw new ValidationException("Converted stock-entry estimates cannot be discarded");
    }
    if (draft.getState() == StockEntryEstimateState.LOCKED) {
      throw new ValidationException(
          "Locked stock-entry estimates already created inventory and cannot be discarded");
    }
    if (draft.getState() == StockEntryEstimateState.DISCARDED) {
      return;
    }
    draft.setState(StockEntryEstimateState.DISCARDED);
    draft.setUpdatedAt(Instant.now());
    stockEntryEstimateRepository.save(draft);
  }

  /**
   * Creates BASIC inventory lots (estimate-only sell) from the draft and marks it LOCKED.
   */
  @Transactional
  public StockEntryEstimateResponse lock(String id, String userId, String shopId) {
    StockEntryEstimate draft = load(id, shopId);
    if (draft.getState() != StockEntryEstimateState.OPEN) {
      throw new ValidationException(
          "Only open stock-entry estimates can be locked (state: " + draft.getState() + ")");
    }
    if (draft.getLines() == null || draft.getLines().isEmpty()) {
      throw new ValidationException("Cannot lock an empty stock-entry estimate");
    }
    // Vendor is optional for no-tax (BASIC) locks; required when the draft carries tax.
    if (linesHaveTaxableFields(draft.getLines()) && !StringUtils.hasText(draft.getVendorId())) {
      throw new ValidationException(
          "Vendor is required before locking an estimate with taxable fields");
    }

    BulkCreateInventoryRequest bulk = toBulkCreateRequest(draft, BillingMode.BASIC);
    BulkCreateInventoryResponse created = inventoryService.bulkCreate(bulk, userId, shopId);

    List<InventoryReceiptResponse> receipts =
        created.getItems() != null ? created.getItems() : List.of();
    for (int i = 0; i < draft.getLines().size() && i < receipts.size(); i++) {
      draft.getLines().get(i).setInventoryId(receipts.get(i).getId());
      draft.getLines().get(i).setBillingMode(BillingMode.BASIC);
    }
    draft.setVendorPurchaseInvoiceId(created.getVendorPurchaseInvoiceId());
    draft.setState(StockEntryEstimateState.LOCKED);
    draft.setLockedAt(Instant.now());
    draft.setLockedByUserId(userId);
    draft.setUpdatedAt(Instant.now());
    draft = stockEntryEstimateRepository.save(draft);
    log.info(
        "Locked stock-entry estimate {} → invoice {} for shop {}",
        draft.getId(),
        draft.getVendorPurchaseInvoiceId(),
        shopId);
    return toResponse(draft);
  }

  /**
   * Marks an OPEN draft CONVERTED after Product Entry saved REGULAR stock with this estimate as
   * source. Does not create inventory itself.
   */
  @Transactional
  public StockEntryEstimateResponse markConverted(
      String id, String vendorPurchaseInvoiceId, String shopId) {
    StockEntryEstimate draft = load(id, shopId);
    if (draft.getState() != StockEntryEstimateState.OPEN) {
      throw new ValidationException(
          "Only open stock-entry estimates can be marked converted (state: "
              + draft.getState()
              + ")");
    }
    if (!StringUtils.hasText(vendorPurchaseInvoiceId)) {
      throw new ValidationException("vendorPurchaseInvoiceId is required");
    }
    draft.setState(StockEntryEstimateState.CONVERTED);
    draft.setConvertedToVendorPurchaseInvoiceId(vendorPurchaseInvoiceId.trim());
    draft.setUpdatedAt(Instant.now());
    draft = stockEntryEstimateRepository.save(draft);
    return toResponse(draft);
  }

  private BulkCreateInventoryRequest toBulkCreateRequest(
      StockEntryEstimate draft, BillingMode billingMode) {
    BulkCreateInventoryRequest bulk = new BulkCreateInventoryRequest();
    bulk.setVendorId(draft.getVendorId());
    VendorPurchaseInvoiceRequest inv = new VendorPurchaseInvoiceRequest();
    if (StringUtils.hasText(draft.getVendorInvoiceNo())) {
      inv.setInvoiceNo(draft.getVendorInvoiceNo().trim());
    }
    inv.setInvoiceDate(draft.getVendorInvoiceDate());
    inv.setLineSubTotal(draft.getLineSubTotal());
    inv.setTaxTotal(draft.getTaxTotal());
    inv.setShippingCharge(draft.getShippingCharge());
    inv.setOtherCharges(draft.getOtherCharges());
    inv.setOverallDiscount(draft.getOverallDiscount());
    inv.setRoundOff(draft.getRoundOff());
    inv.setInvoiceTotal(draft.getInvoiceTotal());
    inv.setPaymentMethod(draft.getPaymentMethod());
    inv.setPaidAmount(draft.getPaidAmount());
    bulk.setVendorPurchaseInvoice(inv);

    List<CreateInventoryItemRequest> items = new ArrayList<>();
    for (StockEntryEstimateLine line : draft.getLines()) {
      CreateInventoryItemRequest item = lineToCreateRequest(line);
      item.setBillingMode(billingMode);
      items.add(item);
    }
    bulk.setItems(items);
    return bulk;
  }

  private CreateInventoryItemRequest lineToCreateRequest(StockEntryEstimateLine line) {
    CreateInventoryItemRequest item = new CreateInventoryItemRequest();
    item.setProductId(line.getProductId());
    item.setBarcode(line.getBarcode());
    item.setName(line.getName());
    item.setDescription(line.getDescription());
    item.setCompanyName(line.getCompanyName());
    item.setMaximumRetailPrice(line.getMaximumRetailPrice());
    item.setCostPrice(line.getCostPrice());
    item.setPriceToRetail(line.getPriceToRetail());
    item.setSellingPrice(line.getSellingPrice());
    item.setRates(line.getRates());
    item.setDefaultRate(line.getDefaultRate());
    item.setSaleAdditionalDiscount(line.getSaleAdditionalDiscount());
    item.setBusinessType(line.getBusinessType());
    item.setLocation(line.getLocation());
    item.setItemType(line.getItemType());
    item.setItemTypeDegree(line.getItemTypeDegree());
    item.setDiscountApplicable(line.getDiscountApplicable());
    item.setPurchaseDate(line.getPurchaseDate());
    item.setCount(line.getCount());
    item.setBaseUnit(line.getBaseUnit());
    item.setUnitsPerPack(line.getUnitsPerPack());
    item.setUnitConversions(line.getUnitConversions());
    item.setThresholdCount(line.getThresholdCount());
    item.setExpiryDate(line.getExpiryDate());
    item.setHsn(line.getHsn());
    item.setBatchNo(line.getBatchNo());
    item.setSchemeType(line.getSchemeType());
    item.setScheme(line.getScheme());
    item.setSchemePayFor(line.getSchemePayFor());
    item.setSchemeFree(line.getSchemeFree());
    item.setSchemePercentage(line.getSchemePercentage());
    item.setPurchaseSchemeType(line.getPurchaseSchemeType());
    item.setPurchaseSchemePayFor(line.getPurchaseSchemePayFor());
    item.setPurchaseSchemeFree(line.getPurchaseSchemeFree());
    item.setPurchaseSchemePercentage(line.getPurchaseSchemePercentage());
    item.setPurchaseAdditionalDiscount(line.getPurchaseAdditionalDiscount());
    item.setSgst(line.getSgst());
    item.setCgst(line.getCgst());
    item.setVerticalFields(line.getVerticalFields());
    return item;
  }

  private void applyUpsert(StockEntryEstimate draft, UpsertStockEntryEstimateRequest request) {
    if (request == null) {
      return;
    }
    if (request.getVendorId() != null) {
      draft.setVendorId(request.getVendorId());
    }
    if (request.getVendorInvoiceNo() != null) {
      draft.setVendorInvoiceNo(request.getVendorInvoiceNo());
    }
    if (request.getVendorInvoiceDate() != null) {
      draft.setVendorInvoiceDate(request.getVendorInvoiceDate());
    }
    if (request.getLineSubTotal() != null) {
      draft.setLineSubTotal(request.getLineSubTotal());
    }
    if (request.getTaxTotal() != null) {
      draft.setTaxTotal(request.getTaxTotal());
    }
    if (request.getShippingCharge() != null) {
      draft.setShippingCharge(request.getShippingCharge());
    }
    if (request.getOtherCharges() != null) {
      draft.setOtherCharges(request.getOtherCharges());
    }
    if (request.getOverallDiscount() != null) {
      draft.setOverallDiscount(request.getOverallDiscount());
    }
    if (request.getRoundOff() != null) {
      draft.setRoundOff(request.getRoundOff());
    }
    if (request.getInvoiceTotal() != null) {
      draft.setInvoiceTotal(request.getInvoiceTotal());
    }
    if (request.getPaymentMethod() != null) {
      draft.setPaymentMethod(request.getPaymentMethod());
    }
    if (request.getCashAmount() != null) {
      draft.setCashAmount(request.getCashAmount());
    }
    if (request.getOnlineAmount() != null) {
      draft.setOnlineAmount(request.getOnlineAmount());
    }
    if (request.getCreditAmount() != null) {
      draft.setCreditAmount(request.getCreditAmount());
    }
    if (request.getPaidAmount() != null) {
      draft.setPaidAmount(request.getPaidAmount());
    }
    if (request.getLines() != null) {
      draft.setLines(new ArrayList<>(request.getLines()));
    }
  }

  private void assertEditable(StockEntryEstimate draft) {
    if (draft.getState() != StockEntryEstimateState.OPEN) {
      throw new ValidationException(
          "Cannot modify stock-entry estimate in state " + draft.getState());
    }
  }

  /** True when any line is REGULAR or carries SGST/CGST — vendor required to lock. */
  private static boolean linesHaveTaxableFields(List<StockEntryEstimateLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return false;
    }
    for (StockEntryEstimateLine line : lines) {
      if (line == null) {
        continue;
      }
      if (line.getBillingMode() == BillingMode.REGULAR) {
        return true;
      }
      if (StringUtils.hasText(line.getSgst()) || StringUtils.hasText(line.getCgst())) {
        return true;
      }
    }
    return false;
  }

  private StockEntryEstimate load(String id, String shopId) {
    return stockEntryEstimateRepository
        .findByIdAndShopId(id, shopId)
        .orElseThrow(
            () -> new ResourceNotFoundException("StockEntryEstimate", "id", id));
  }

  private StockEntryEstimateListResponse.StockEntryEstimateSummary toSummary(
      StockEntryEstimate draft) {
    int count = draft.getLines() != null ? draft.getLines().size() : 0;
    return new StockEntryEstimateListResponse.StockEntryEstimateSummary(
        draft.getId(),
        draft.getEstimateNo(),
        draft.getState(),
        draft.getVendorId(),
        draft.getVendorInvoiceNo(),
        count,
        draft.getInvoiceTotal(),
        draft.getUpdatedAt(),
        draft.getCreatedAt());
  }

  private StockEntryEstimateResponse toResponse(StockEntryEstimate draft) {
    return new StockEntryEstimateResponse(
        draft.getId(),
        draft.getEstimateNo(),
        draft.getState(),
        draft.getVendorId(),
        draft.getVendorInvoiceNo(),
        draft.getVendorInvoiceDate(),
        draft.getLineSubTotal(),
        draft.getTaxTotal(),
        draft.getShippingCharge(),
        draft.getOtherCharges(),
        draft.getOverallDiscount(),
        draft.getRoundOff(),
        draft.getInvoiceTotal(),
        draft.getPaymentMethod(),
        draft.getCashAmount(),
        draft.getOnlineAmount(),
        draft.getCreditAmount(),
        draft.getPaidAmount(),
        draft.getLines(),
        draft.getVendorPurchaseInvoiceId(),
        draft.getConvertedToVendorPurchaseInvoiceId(),
        draft.getLockedAt(),
        draft.getCreatedAt(),
        draft.getUpdatedAt());
  }
}
