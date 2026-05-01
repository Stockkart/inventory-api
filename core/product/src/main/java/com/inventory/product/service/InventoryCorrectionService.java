package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.InventoryCorrection;
import com.inventory.product.domain.model.InventoryCorrectionLine;
import com.inventory.product.domain.model.enums.InventoryCorrectionLineStatus;
import com.inventory.product.domain.model.enums.InventoryCorrectionStatus;
import com.inventory.product.domain.repository.InventoryCorrectionRepository;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.rest.dto.request.CreateInventoryCorrectionRequest;
import com.inventory.product.rest.dto.request.InventoryCorrectionLineRequest;
import com.inventory.product.rest.dto.response.InventoryCorrectionDto;
import com.inventory.product.rest.dto.response.InventoryCorrectionLineDto;
import com.inventory.product.rest.dto.response.InventoryCorrectionListResponse;
import com.inventory.product.rest.dto.response.PageMeta;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class InventoryCorrectionService {

  @Autowired private InventoryCorrectionRepository inventoryCorrectionRepository;
  @Autowired private InventoryRepository inventoryRepository;

  @Transactional
  public InventoryCorrectionDto createPending(
      CreateInventoryCorrectionRequest request, String shopId, String userId) {
    if (request == null || request.getLines() == null || request.getLines().isEmpty()) {
      throw new ValidationException("At least one correction line is required");
    }

    List<InventoryCorrectionLine> lines = new ArrayList<>();
    for (InventoryCorrectionLineRequest lineReq : request.getLines()) {
      if (!StringUtils.hasText(lineReq.getInventoryId())) {
        throw new ValidationException("Inventory ID is required in correction lines");
      }
      if (lineReq.getRequestedCurrentCount() == null
          || lineReq.getRequestedCurrentCount().compareTo(BigDecimal.ZERO) < 0) {
        throw new ValidationException("Requested current quantity must be >= 0");
      }
      String inventoryId = lineReq.getInventoryId().trim();
      Inventory inv =
          inventoryRepository
              .findById(inventoryId)
              .orElseThrow(() -> new ResourceNotFoundException("Inventory", "id", inventoryId));
      if (!shopId.equals(inv.getShopId())) {
        throw new ValidationException("Inventory does not belong to the authenticated shop");
      }

      BigDecimal requested = lineReq.getRequestedCurrentCount().setScale(4, RoundingMode.HALF_UP);
      int requestedBase = toBaseQuantity(requested, inv);

      InventoryCorrectionLine line = new InventoryCorrectionLine();
      line.setLineId(UUID.randomUUID().toString());
      line.setInventoryId(inv.getId());
      line.setProductName(inv.getName());
      line.setPreviousCurrentCount(inv.getCurrentCount());
      line.setPreviousCurrentBaseCount(resolveCurrentBase(inv));
      line.setRequestedCurrentCount(requested);
      line.setRequestedCurrentBaseCount(requestedBase);
      line.setStatus(InventoryCorrectionLineStatus.PENDING);
      lines.add(line);
    }

    Instant now = Instant.now();
    InventoryCorrection correction = new InventoryCorrection();
    correction.setShopId(shopId);
    correction.setVendorPurchaseInvoiceId(request.getVendorPurchaseInvoiceId());
    correction.setInvoiceNo(request.getInvoiceNo());
    correction.setVendorId(request.getVendorId());
    correction.setVendorName(request.getVendorName());
    correction.setNote(request.getNote());
    correction.setStatus(InventoryCorrectionStatus.PENDING);
    correction.setCreatedAt(now);
    correction.setUpdatedAt(now);
    correction.setCreatedByUserId(userId);
    correction.setLines(lines);

    return toDto(inventoryCorrectionRepository.save(correction));
  }

  @Transactional(readOnly = true)
  public InventoryCorrectionListResponse list(
      String shopId, String status, int page, int size) {
    PageRequest pageable =
        PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    Page<InventoryCorrection> p;
    if (StringUtils.hasText(status)) {
      InventoryCorrectionStatus parsed = InventoryCorrectionStatus.valueOf(status.trim().toUpperCase());
      p = inventoryCorrectionRepository.findByShopIdAndStatus(shopId, parsed, pageable);
    } else {
      p = inventoryCorrectionRepository.findByShopId(shopId, pageable);
    }

    List<InventoryCorrectionDto> rows = p.getContent().stream().map(this::toDto).toList();
    return new InventoryCorrectionListResponse(
        rows, new PageMeta(page, size, p.getTotalElements(), p.getTotalPages()));
  }

  @Transactional(readOnly = true)
  public InventoryCorrectionDto getById(String id, String shopId) {
    InventoryCorrection c =
        inventoryCorrectionRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Inventory correction", "id", id));
    if (!shopId.equals(c.getShopId())) {
      throw new ValidationException("Correction does not belong to authenticated shop");
    }
    return toDto(c);
  }

  @Transactional
  public InventoryCorrectionDto approveLine(
      String correctionId, String lineId, String shopId, String userId) {
    InventoryCorrection correction = loadOwnedCorrection(correctionId, shopId);
    InventoryCorrectionLine line = loadPendingLine(correction, lineId);

    Inventory inventory =
        inventoryRepository
            .findById(line.getInventoryId())
            .orElseThrow(
                () -> new ResourceNotFoundException("Inventory", "id", line.getInventoryId()));
    if (!shopId.equals(inventory.getShopId())) {
      throw new ValidationException("Inventory does not belong to authenticated shop");
    }

    inventory.setCurrentCount(line.getRequestedCurrentCount());
    inventory.setCurrentBaseCount(line.getRequestedCurrentBaseCount());
    inventory.setUpdatedAt(Instant.now());
    inventoryRepository.save(inventory);

    line.setStatus(InventoryCorrectionLineStatus.APPROVED);
    line.setProcessedAt(Instant.now());
    line.setProcessedByUserId(userId);
    line.setRejectionReason(null);

    recalcCorrectionStatus(correction);
    correction.setUpdatedAt(Instant.now());
    return toDto(inventoryCorrectionRepository.save(correction));
  }

  @Transactional
  public InventoryCorrectionDto rejectLine(
      String correctionId, String lineId, String reason, String shopId, String userId) {
    InventoryCorrection correction = loadOwnedCorrection(correctionId, shopId);
    InventoryCorrectionLine line = loadPendingLine(correction, lineId);
    line.setStatus(InventoryCorrectionLineStatus.REJECTED);
    line.setProcessedAt(Instant.now());
    line.setProcessedByUserId(userId);
    line.setRejectionReason(reason);
    recalcCorrectionStatus(correction);
    correction.setUpdatedAt(Instant.now());
    return toDto(inventoryCorrectionRepository.save(correction));
  }

  private InventoryCorrection loadOwnedCorrection(String correctionId, String shopId) {
    InventoryCorrection correction =
        inventoryCorrectionRepository
            .findById(correctionId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Inventory correction", "id", correctionId));
    if (!shopId.equals(correction.getShopId())) {
      throw new ValidationException("Correction does not belong to authenticated shop");
    }
    return correction;
  }

  private InventoryCorrectionLine loadPendingLine(InventoryCorrection correction, String lineId) {
    InventoryCorrectionLine line =
        correction.getLines().stream()
            .filter(l -> lineId.equals(l.getLineId()))
            .findFirst()
            .orElseThrow(
                () -> new ResourceNotFoundException("Inventory correction line", "lineId", lineId));
    if (line.getStatus() != InventoryCorrectionLineStatus.PENDING) {
      throw new ValidationException("Correction line is already processed");
    }
    return line;
  }

  private void recalcCorrectionStatus(InventoryCorrection correction) {
    List<InventoryCorrectionLineStatus> statuses =
        correction.getLines().stream().map(InventoryCorrectionLine::getStatus).collect(Collectors.toList());
    boolean anyPending = statuses.stream().anyMatch(s -> s == InventoryCorrectionLineStatus.PENDING);
    boolean anyApproved = statuses.stream().anyMatch(s -> s == InventoryCorrectionLineStatus.APPROVED);
    boolean anyRejected = statuses.stream().anyMatch(s -> s == InventoryCorrectionLineStatus.REJECTED);

    if (anyPending && (anyApproved || anyRejected)) {
      correction.setStatus(InventoryCorrectionStatus.PARTIALLY_APPROVED);
      return;
    }
    if (anyPending) {
      correction.setStatus(InventoryCorrectionStatus.PENDING);
      return;
    }
    if (anyApproved && !anyRejected) {
      correction.setStatus(InventoryCorrectionStatus.APPLIED);
      return;
    }
    if (anyRejected && !anyApproved) {
      correction.setStatus(InventoryCorrectionStatus.REJECTED);
      return;
    }
    correction.setStatus(InventoryCorrectionStatus.PARTIALLY_APPROVED);
  }

  private int toBaseQuantity(BigDecimal displayQuantity, Inventory inventory) {
    int factor = 1;
    if (inventory.getUnitConversions() != null
        && inventory.getUnitConversions().getFactor() != null
        && inventory.getUnitConversions().getFactor() > 0) {
      factor = inventory.getUnitConversions().getFactor();
    }
    return displayQuantity
        .multiply(BigDecimal.valueOf(factor))
        .setScale(0, RoundingMode.HALF_UP)
        .intValue();
  }

  private Integer resolveCurrentBase(Inventory inventory) {
    if (inventory.getCurrentBaseCount() != null) return inventory.getCurrentBaseCount();
    if (inventory.getCurrentCount() == null) return null;
    return toBaseQuantity(inventory.getCurrentCount(), inventory);
  }

  private InventoryCorrectionDto toDto(InventoryCorrection c) {
    List<InventoryCorrectionLineDto> lines =
        c.getLines() == null
            ? List.of()
            : c.getLines().stream()
                .map(
                    l ->
                        new InventoryCorrectionLineDto(
                            l.getLineId(),
                            l.getInventoryId(),
                            l.getProductName(),
                            l.getPreviousCurrentCount(),
                            l.getPreviousCurrentBaseCount(),
                            l.getRequestedCurrentCount(),
                            l.getRequestedCurrentBaseCount(),
                            l.getStatus(),
                            l.getProcessedAt(),
                            l.getProcessedByUserId(),
                            l.getRejectionReason()))
                .toList();
    return new InventoryCorrectionDto(
        c.getId(),
        c.getVendorPurchaseInvoiceId(),
        c.getInvoiceNo(),
        c.getVendorId(),
        c.getVendorName(),
        c.getStatus(),
        c.getNote(),
        c.getCreatedAt(),
        c.getCreatedByUserId(),
        c.getUpdatedAt(),
        lines);
  }
}

