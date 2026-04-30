package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.product.rest.dto.response.PageMeta;
import com.inventory.product.rest.dto.response.VendorPurchaseInvoiceDetailDto;
import com.inventory.product.rest.dto.response.VendorPurchaseInvoiceLineDto;
import com.inventory.product.rest.dto.response.VendorPurchaseInvoiceListResponse;
import com.inventory.product.rest.dto.response.VendorPurchaseInvoiceSummaryDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class VendorPurchaseInvoiceService {

  @Autowired
  private VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;

  @Autowired private VendorRepository vendorRepository;

  public VendorPurchaseInvoiceListResponse list(String shopId, int page, int size) {
    PageRequest pageable =
        PageRequest.of(
            page,
            size,
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    Page<VendorPurchaseInvoice> p =
        vendorPurchaseInvoiceRepository.findByShopId(shopId, pageable);
    Map<String, String> vendorNameById =
        loadVendorNames(
            p.getContent().stream().map(VendorPurchaseInvoice::getVendorId).collect(Collectors.toSet()));
    List<VendorPurchaseInvoiceSummaryDto> summaries =
        p.getContent().stream()
            .map((e) -> toSummary(e, vendorNameById))
            .collect(Collectors.toList());
    PageMeta meta =
        new PageMeta(page, size, p.getTotalElements(), p.getTotalPages());
    return new VendorPurchaseInvoiceListResponse(summaries, meta);
  }

  public VendorPurchaseInvoiceDetailDto getById(String id, String shopId) {
    VendorPurchaseInvoice inv =
        vendorPurchaseInvoiceRepository
            .findByIdAndShopId(id, shopId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Vendor purchase invoice not found: " + id));
    return toDetail(inv);
  }

  private Map<String, String> loadVendorNames(Set<String> vendorIds) {
    if (vendorIds == null || vendorIds.isEmpty()) {
      return Collections.emptyMap();
    }
    Set<String> ids = new HashSet<>();
    for (String id : vendorIds) {
      if (id != null && !id.isBlank()) {
        ids.add(id.trim());
      }
    }
    if (ids.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, String> out = new HashMap<>();
    for (Vendor v : vendorRepository.findAllById(ids)) {
      if (v.getId() != null) {
        out.put(v.getId(), v.getName());
      }
    }
    return out;
  }

  private String resolveVendorName(String vendorId, Map<String, String> vendorNameById) {
    if (vendorId == null || vendorId.isBlank()) {
      return null;
    }
    return vendorNameById.get(vendorId.trim());
  }

  private VendorPurchaseInvoiceSummaryDto toSummary(
      VendorPurchaseInvoice e, Map<String, String> vendorNameById) {
    int lineCount = e.getLines() != null ? e.getLines().size() : 0;
    String vid = e.getVendorId();
    String vname = resolveVendorName(vid, vendorNameById);
    return new VendorPurchaseInvoiceSummaryDto(
        e.getId(),
        vid,
        vname,
        e.getInvoiceNo(),
        e.getInvoiceDate(),
        e.getInvoiceTotal(),
        lineCount,
        e.getCreatedAt(),
        e.getSynthetic(),
        e.getLegacyLotId());
  }

  private VendorPurchaseInvoiceDetailDto toDetail(VendorPurchaseInvoice e) {
    VendorPurchaseInvoiceDetailDto dto = new VendorPurchaseInvoiceDetailDto();
    dto.setId(e.getId());
    dto.setVendorId(e.getVendorId());
    String vid = e.getVendorId();
    if (vid != null && !vid.isBlank()) {
      dto.setVendorName(vendorRepository.findById(vid.trim()).map(Vendor::getName).orElse(null));
    }
    dto.setInvoiceNo(e.getInvoiceNo());
    dto.setInvoiceDate(e.getInvoiceDate());
    dto.setLineSubTotal(e.getLineSubTotal());
    dto.setTaxTotal(e.getTaxTotal());
    dto.setShippingCharge(e.getShippingCharge());
    dto.setOtherCharges(e.getOtherCharges());
    dto.setRoundOff(e.getRoundOff());
    dto.setInvoiceTotal(e.getInvoiceTotal());
    dto.setCreatedAt(e.getCreatedAt());
    dto.setSynthetic(e.getSynthetic());
    dto.setLegacyLotId(e.getLegacyLotId());
    if (e.getLines() != null) {
      dto.setLines(
          e.getLines().stream().map(this::toLineDto).collect(Collectors.toList()));
    }
    return dto;
  }

  private VendorPurchaseInvoiceLineDto toLineDto(VendorPurchaseInvoiceLine line) {
    return new VendorPurchaseInvoiceLineDto(
        line.getLineIndex(),
        line.getName(),
        line.getBarcode(),
        line.getCount(),
        line.getCostPrice(),
        line.getInventoryId());
  }
}
