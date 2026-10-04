package com.inventory.product.service;

import com.inventory.product.rest.dto.request.AmendVendorPurchaseInvoiceRequest;
import com.inventory.product.validation.VendorPurchaseInvoiceValidator;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import java.time.Instant;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.product.rest.dto.response.AmendInvoicePreviewResponse;
import com.inventory.product.rest.dto.response.InvoiceHeaderFiguresDto;
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
@Slf4j
public class VendorPurchaseInvoiceService {

  @Autowired
  private VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;

  @Autowired private VendorRepository vendorRepository;

  @Autowired private VendorPurchaseInvoiceValidator vendorPurchaseInvoiceValidator;

  @Autowired private PurchaseTaxRecorder purchaseTaxRecorder;

  @Autowired private InventoryService inventoryService;

  /**
   * Corrects a purchase invoice's header against the paper bill and re-resolves its tax.
   *
   * <p>For a bill saved with the wrong tax treatment, discount, round-off or charges. The goods are
   * already in stock, so re-entering the bill would double them. The operator sees the corrected
   * figures first ({@link #previewAmendment}); a correction that would change nothing is refused.
   *
   * <p>Only the header moves. The lines are what the stock was created from, and changing a
   * quantity or a cost here would leave the invoice describing goods that were never received.
   *
   * <p>The previous header is kept, along with who changed it and why. A purchase invoice is the
   * evidence behind an input credit; a figure that changes with no account of itself is worse
   * than the wrong figure, which at least had a bill behind it.
   */
  @Transactional
  public VendorPurchaseInvoiceDetailDto amendHeader(
      String id, String shopId, String userId, AmendVendorPurchaseInvoiceRequest request) {

    if (request == null) {
      throw new ValidationException("Nothing to amend");
    }
    if (!StringUtils.hasText(request.getReason())) {
      throw new ValidationException("A reason is required to amend an invoice");
    }

    VendorPurchaseInvoice invoice = load(id, shopId);
    VendorPurchaseInvoice.AmendedHeaderSnapshot before = snapshot(invoice);
    applyAmendment(invoice, request);
    VendorPurchaseInvoice.AmendedHeaderSnapshot after = snapshot(invoice);
    // A correction that moves nothing would still reverse and repost the journal, leaving a pair
    // of entries that say a change happened when none did. Refused, so the operator is told.
    if (changedFields(before, after).isEmpty()) {
      throw new ValidationException("Nothing would change on this invoice");
    }

    invoice.setPreviousHeader(before);
    invoice.setAmendedAt(Instant.now());
    invoice.setAmendedByUserId(userId);
    invoice.setAmendmentReason(request.getReason().trim());

    // The journal carried the old header; reverse it and post the corrected one.
    inventoryService.repostAccountingAfterAmend(invoice, shopId, userId, invoice.getAmendmentReason());
    VendorPurchaseInvoice saved = vendorPurchaseInvoiceRepository.save(invoice);

    log.info("Invoice {} (shop {}) amended by {}: {} -- invoice total {} -> {}, tax {} -> {}",
        saved.getInvoiceNo(), shopId, userId, saved.getAmendmentReason(),
        before.getInvoiceTotal(), saved.getInvoiceTotal(), before.getTaxTotal(), saved.getTaxTotal());

    return getById(saved.getId(), shopId);
  }

  /**
   * What {@link #amendHeader} would do, worked out on a copy and not saved. The operator sees the
   * saved figures beside the corrected ones, and which of them move, before confirming -- a
   * correction changes the tax claimed and the journal, so it is not made without being shown.
   */
  public AmendInvoicePreviewResponse previewAmendment(
      String id, String shopId, AmendVendorPurchaseInvoiceRequest request) {
    VendorPurchaseInvoice invoice = load(id, shopId);
    VendorPurchaseInvoice.AmendedHeaderSnapshot before = snapshot(invoice);
    VendorPurchaseInvoice copy = copyForPreview(invoice);
    if (request != null) {
      applyAmendment(copy, request);
    }
    VendorPurchaseInvoice.AmendedHeaderSnapshot after = snapshot(copy);
    List<String> changed = changedFields(before, after);
    return new AmendInvoicePreviewResponse(
        figures(before), figures(after), changed, !changed.isEmpty());
  }

  private VendorPurchaseInvoice load(String id, String shopId) {
    return vendorPurchaseInvoiceRepository
        .findById(id)
        .filter(found -> shopId.equals(found.getShopId()))
        .orElseThrow(() -> new ResourceNotFoundException("Purchase invoice not found: " + id));
  }

  /**
   * Applies the requested header changes and works the totals out again from the lines. Absent
   * means "leave it as it stands"; the subtotal, tax and invoice total are never taken from the
   * request.
   */
  private void applyAmendment(VendorPurchaseInvoice invoice, AmendVendorPurchaseInvoiceRequest request) {
    if (request.getShippingCharge() != null) {
      invoice.setShippingCharge(request.getShippingCharge());
    }
    if (request.getOtherCharges() != null) invoice.setOtherCharges(request.getOtherCharges());
    if (request.getOverallDiscount() != null) {
      invoice.setOverallDiscount(request.getOverallDiscount());
    }
    if (request.getRoundOff() != null) invoice.setRoundOff(request.getRoundOff());
    if (request.getTaxTreatment() != null) invoice.setTaxTreatment(request.getTaxTreatment());

    // The same shape check registration applies, so an amendment cannot introduce what it
    // rejects.
    vendorPurchaseInvoiceValidator.validateHeaderAmounts(
        invoice.getShippingCharge(), invoice.getOtherCharges(), invoice.getOverallDiscount());

    // Cleared, then filled from the lines. Non-fatal, as at registration.
    invoice.setLineSubTotal(null);
    invoice.setTaxTotal(null);
    invoice.setInvoiceTotal(null);
    purchaseTaxRecorder.record(invoice);
  }

  /** A copy whose header and lines can be worked on without touching the saved invoice. */
  private static VendorPurchaseInvoice copyForPreview(VendorPurchaseInvoice invoice) {
    VendorPurchaseInvoice copy = new VendorPurchaseInvoice();
    org.springframework.beans.BeanUtils.copyProperties(invoice, copy, "lines");
    List<VendorPurchaseInvoiceLine> lines = new ArrayList<>();
    for (VendorPurchaseInvoiceLine line : invoice.getLines() == null
        ? List.<VendorPurchaseInvoiceLine>of() : invoice.getLines()) {
      VendorPurchaseInvoiceLine lineCopy = new VendorPurchaseInvoiceLine();
      org.springframework.beans.BeanUtils.copyProperties(line, lineCopy);
      lines.add(lineCopy);
    }
    copy.setLines(lines);
    return copy;
  }

  private static VendorPurchaseInvoice.AmendedHeaderSnapshot snapshot(VendorPurchaseInvoice invoice) {
    return new VendorPurchaseInvoice.AmendedHeaderSnapshot(
        invoice.getLineSubTotal(), invoice.getTaxTotal(), invoice.getShippingCharge(),
        invoice.getOtherCharges(), invoice.getOverallDiscount(), invoice.getRoundOff(),
        invoice.getInvoiceTotal(), invoice.getTaxTreatment());
  }

  private static InvoiceHeaderFiguresDto figures(VendorPurchaseInvoice.AmendedHeaderSnapshot s) {
    if (s == null) {
      return null;
    }
    return new InvoiceHeaderFiguresDto(
        s.getLineSubTotal(), s.getTaxTotal(), s.getShippingCharge(), s.getOtherCharges(),
        s.getOverallDiscount(), s.getRoundOff(), s.getInvoiceTotal(),
        s.getTaxTreatment() != null ? s.getTaxTreatment().name() : null);
  }

  /** The header figures that differ between two snapshots, compared by value (2.50 equals 2.5). */
  static List<String> changedFields(
      VendorPurchaseInvoice.AmendedHeaderSnapshot a, VendorPurchaseInvoice.AmendedHeaderSnapshot b) {
    List<String> out = new ArrayList<>();
    if (differs(a.getLineSubTotal(), b.getLineSubTotal())) out.add("lineSubTotal");
    if (differs(a.getTaxTotal(), b.getTaxTotal())) out.add("taxTotal");
    if (differs(a.getShippingCharge(), b.getShippingCharge())) out.add("shippingCharge");
    if (differs(a.getOtherCharges(), b.getOtherCharges())) out.add("otherCharges");
    if (differs(a.getOverallDiscount(), b.getOverallDiscount())) out.add("overallDiscount");
    if (differs(a.getRoundOff(), b.getRoundOff())) out.add("roundOff");
    if (differs(a.getInvoiceTotal(), b.getInvoiceTotal())) out.add("invoiceTotal");
    if (a.getTaxTreatment() != b.getTaxTreatment()) out.add("taxTreatment");
    return out;
  }

  private static boolean differs(java.math.BigDecimal x, java.math.BigDecimal y) {
    java.math.BigDecimal left = x == null ? java.math.BigDecimal.ZERO : x;
    java.math.BigDecimal right = y == null ? java.math.BigDecimal.ZERO : y;
    return left.compareTo(right) != 0;
  }

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
    dto.setTaxTreatment(e.getTaxTreatment() != null ? e.getTaxTreatment().name() : null);
    dto.setAmendedAt(e.getAmendedAt());
    dto.setAmendedByUserId(e.getAmendedByUserId());
    dto.setAmendmentReason(e.getAmendmentReason());
    dto.setPreviousHeader(figures(e.getPreviousHeader()));
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
