package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import com.inventory.accounting.service.PurchaseJournalService;
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
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

@Service
public class VendorPurchaseInvoiceService {

  @Autowired
  private VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;

  @Autowired private VendorRepository vendorRepository;

  @Autowired private JournalEntryRepository journalEntryRepository;

  public VendorPurchaseInvoiceListResponse list(String shopId, int page, int size, String query) {
    if (query != null && !query.trim().isEmpty()) {
      Pattern pattern;
      try {
        pattern =
            Pattern.compile(
                query.trim(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
      } catch (PatternSyntaxException e) {
        throw new ValidationException(
            "Invalid search pattern (regular expression): " + e.getDescription());
      }
      List<VendorPurchaseInvoice> all = vendorPurchaseInvoiceRepository.findByShopId(shopId);
      Map<String, String> vendorNameById =
          loadVendorNames(
              all.stream().map(VendorPurchaseInvoice::getVendorId).collect(Collectors.toSet()));
      List<VendorPurchaseInvoice> filtered = new ArrayList<>();
      for (VendorPurchaseInvoice inv : all) {
        if (matchesInvoiceSearch(inv, pattern, vendorNameById)) {
          filtered.add(inv);
        }
      }
      filtered.sort(
          (a, b) -> {
            if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
            if (a.getCreatedAt() == null) return 1;
            if (b.getCreatedAt() == null) return -1;
            int cmp = b.getCreatedAt().compareTo(a.getCreatedAt());
            if (cmp != 0) return cmp;
            String aId = a.getId() != null ? a.getId() : "";
            String bId = b.getId() != null ? b.getId() : "";
            return bId.compareTo(aId);
          });
      int from = Math.min(page * size, filtered.size());
      int to = Math.min(from + size, filtered.size());
      List<VendorPurchaseInvoice> slice = filtered.subList(from, to);
      List<VendorPurchaseInvoiceSummaryDto> summaries =
          slice.stream().map((e) -> toSummary(e, vendorNameById)).collect(Collectors.toList());
      int totalPages = size <= 0 ? 1 : (int) Math.ceil((double) filtered.size() / size);
      return new VendorPurchaseInvoiceListResponse(
          summaries, new PageMeta(page, size, filtered.size(), totalPages));
    }

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
    VendorPurchaseInvoiceDetailDto dto = toDetail(inv);
    attachAccountingJournalPointer(dto, shopId);
    return dto;
  }

  private void attachAccountingJournalPointer(VendorPurchaseInvoiceDetailDto dto, String shopId) {
    if (dto.getId() == null || shopId == null || shopId.isBlank()) {
      return;
    }
    String key = PurchaseJournalService.PURCHASE_SOURCE_PREFIX + dto.getId();
    journalEntryRepository.findByShopIdAndSourceKey(shopId.trim(), key).ifPresent(j -> dto.setAccountingJournalEntryId(j.getId()));
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

  /**
   * True if the regex matches any of: line product {@code name} or {@code barcode}, {@link
   * VendorPurchaseInvoice#getInvoiceNo()}, or resolved vendor display name.
   */
  private boolean matchesInvoiceSearch(
      VendorPurchaseInvoice inv,
      Pattern pattern,
      Map<String, String> vendorNameById) {
    if (regexFind(pattern, inv.getInvoiceNo())) {
      return true;
    }
    if (regexFind(pattern, resolveVendorName(inv.getVendorId(), vendorNameById))) {
      return true;
    }
    return matchesAnyPurchaseLine(inv, pattern);
  }

  /** True if any persisted invoice line matches the regex against stored product name or barcode. */
  private boolean matchesAnyPurchaseLine(VendorPurchaseInvoice inv, Pattern pattern) {
    List<VendorPurchaseInvoiceLine> lines = inv.getLines();
    if (lines == null || lines.isEmpty()) {
      return false;
    }
    for (VendorPurchaseInvoiceLine line : lines) {
      if (regexFind(pattern, line.getName())) {
        return true;
      }
      if (regexFind(pattern, line.getBarcode())) {
        return true;
      }
    }
    return false;
  }

  private static boolean regexFind(Pattern pattern, String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    return pattern.matcher(value.trim()).find();
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
        e.getPaymentMethod(),
        e.getPaidAmount(),
        e.getSplitAmounts(),
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
    dto.setPaymentMethod(e.getPaymentMethod());
    dto.setPaidAmount(e.getPaidAmount());
    dto.setSplitAmounts(e.getSplitAmounts());
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
