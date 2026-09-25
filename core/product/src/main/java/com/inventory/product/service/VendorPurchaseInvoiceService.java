package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

@Service
public class VendorPurchaseInvoiceService {

  @Autowired
  private VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;

  @Autowired private VendorRepository vendorRepository;

  /**
   * Purchase bills are dated on the shop's clock, as sales are (see {@code CheckoutService}). A
   * filter day read in UTC would move its edges by five and a half hours.
   */
  private static final ZoneId BILL_DATE_ZONE = ZoneId.of("Asia/Kolkata");

  /** Stand-ins for a missing bound, so the period query always has two edges. */
  private static final Instant OPEN_START = Instant.EPOCH;
  private static final Instant OPEN_END = Instant.parse("9999-12-31T00:00:00Z");

  public VendorPurchaseInvoiceListResponse list(String shopId, int page, int size, String query) {
    return list(shopId, page, size, query, null, null, null, null);
  }

  /**
   * Lists a shop's purchase bills, newest first, with every criterion applied before paging.
   *
   * <p>{@code query} is tried against the invoice number, the vendor name and each line's name and
   * barcode, and any one of them may match. {@code invoiceNo} and {@code vendor} each match their
   * own field only, and every criterion given must hold. {@code from} and {@code to} are days on
   * the bill, both inclusive.
   *
   * <p>With a date bound the bills are ordered by the date on the bill, not by when they were
   * entered: a range asks about when goods were bought, and a bill keyed in late belongs where its
   * date puts it.
   */
  public VendorPurchaseInvoiceListResponse list(
      String shopId,
      int page,
      int size,
      String query,
      String invoiceNo,
      String vendor,
      LocalDate from,
      LocalDate to) {
    if (from != null && to != null && from.isAfter(to)) {
      throw new ValidationException("The From date is after the To date");
    }
    Pattern anyField = compileSearch(query);
    Pattern invoiceNoPattern = compileSearch(invoiceNo);
    Pattern vendorPattern = compileSearch(vendor);
    Instant start = from != null ? from.atStartOfDay(BILL_DATE_ZONE).toInstant() : null;
    // A day bound is inclusive, so the period runs to the start of the day after.
    Instant end = to != null ? to.plusDays(1).atStartOfDay(BILL_DATE_ZONE).toInstant() : null;
    boolean byBillDate = start != null || end != null;

    if (anyField == null && invoiceNoPattern == null && vendorPattern == null) {
      Page<VendorPurchaseInvoice> p =
          byBillDate
              ? vendorPurchaseInvoiceRepository.findByShopIdAndInvoiceDateInPeriod(
                  shopId,
                  start != null ? start : OPEN_START,
                  end != null ? end : OPEN_END,
                  PageRequest.of(
                      page, size, Sort.by(Sort.Order.desc("invoiceDate"), Sort.Order.desc("id"))))
              : vendorPurchaseInvoiceRepository.findByShopId(
                  shopId,
                  PageRequest.of(
                      page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
      Map<String, String> vendorNameById =
          loadVendorNames(
              p.getContent().stream()
                  .map(VendorPurchaseInvoice::getVendorId)
                  .collect(Collectors.toSet()));
      List<VendorPurchaseInvoiceSummaryDto> summaries =
          p.getContent().stream()
              .map((e) -> toSummary(e, vendorNameById))
              .collect(Collectors.toList());
      PageMeta meta = new PageMeta(page, size, p.getTotalElements(), p.getTotalPages());
      return new VendorPurchaseInvoiceListResponse(summaries, meta);
    }

    // The patterns are regular expressions, and a vendor is matched by name, which lives in
    // another collection, so the text criteria are applied here over the whole shop.
    List<VendorPurchaseInvoice> all = vendorPurchaseInvoiceRepository.findByShopId(shopId);
    Map<String, String> vendorNameById =
        loadVendorNames(
            all.stream().map(VendorPurchaseInvoice::getVendorId).collect(Collectors.toSet()));
    List<VendorPurchaseInvoice> filtered = new ArrayList<>();
    for (VendorPurchaseInvoice inv : all) {
      if (byBillDate && !inPeriod(inv.getInvoiceDate(), start, end)) {
        continue;
      }
      if (anyField != null && !matchesInvoiceSearch(inv, anyField, vendorNameById)) {
        continue;
      }
      if (invoiceNoPattern != null && !regexFind(invoiceNoPattern, inv.getInvoiceNo())) {
        continue;
      }
      if (vendorPattern != null
          && !regexFind(vendorPattern, resolveVendorName(inv.getVendorId(), vendorNameById))) {
        continue;
      }
      filtered.add(inv);
    }
    Function<VendorPurchaseInvoice, Instant> sortKey =
        byBillDate ? VendorPurchaseInvoice::getInvoiceDate : VendorPurchaseInvoice::getCreatedAt;
    filtered.sort(
        Comparator.comparing(sortKey, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(
                (VendorPurchaseInvoice inv) -> inv.getId() != null ? inv.getId() : "",
                Comparator.reverseOrder()));
    int fromIndex = Math.min(page * size, filtered.size());
    int toIndex = Math.min(fromIndex + size, filtered.size());
    List<VendorPurchaseInvoiceSummaryDto> summaries =
        filtered.subList(fromIndex, toIndex).stream()
            .map((e) -> toSummary(e, vendorNameById))
            .collect(Collectors.toList());
    int totalPages = size <= 0 ? 1 : (int) Math.ceil((double) filtered.size() / size);
    return new VendorPurchaseInvoiceListResponse(
        summaries, new PageMeta(page, size, filtered.size(), totalPages));
  }

  /** Null for a blank pattern, which filters nothing. */
  private static Pattern compileSearch(String pattern) {
    if (pattern == null || pattern.trim().isEmpty()) {
      return null;
    }
    try {
      return Pattern.compile(pattern.trim(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    } catch (PatternSyntaxException e) {
      throw new ValidationException(
          "Invalid search pattern (regular expression): " + e.getDescription());
    }
  }

  /** Half-open, as the repository query is. A bill with no date is in no period. */
  private static boolean inPeriod(Instant at, Instant start, Instant end) {
    if (at == null) {
      return false;
    }
    return (start == null || !at.isBefore(start)) && (end == null || at.isBefore(end));
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
    dto.setOverallDiscount(e.getOverallDiscount());
    dto.setRoundOff(e.getRoundOff());
    dto.setInvoiceTotal(e.getInvoiceTotal());
    dto.setPaymentMethod(e.getPaymentMethod());
    dto.setPaidAmount(e.getPaidAmount());
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
