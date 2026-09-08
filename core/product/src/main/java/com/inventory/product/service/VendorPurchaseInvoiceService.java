package com.inventory.product.service;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.rest.dto.request.AmendVendorPurchaseInvoiceRequest;
import com.inventory.product.tax.PurchaseTaxBasis;
import com.inventory.product.tax.PurchaseTaxBasisResolver;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
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

  @Autowired private InventoryRepository inventoryRepository;

  @Autowired private PricingRepository pricingRepository;

  /**
   * Corrects a purchase invoice's header against the paper bill and re-resolves its tax.
   *
   * <p>This is the way out of a flagged invoice. Registration warns when the header does not
   * reconcile, but until now there was nowhere to act on that: the goods were already in stock,
   * so re-entering the bill would have doubled them, and the only remaining route was the
   * database. An operator holding the paper can now put the figures right.
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

    VendorPurchaseInvoice invoice =
        vendorPurchaseInvoiceRepository
            .findById(id)
            .filter(found -> shopId.equals(found.getShopId()))
            .orElseThrow(() -> new ResourceNotFoundException("Purchase invoice not found: " + id));

    VendorPurchaseInvoice.AmendedHeaderSnapshot before =
        new VendorPurchaseInvoice.AmendedHeaderSnapshot(
            invoice.getLineSubTotal(), invoice.getTaxTotal(), invoice.getShippingCharge(),
            invoice.getOtherCharges(), invoice.getOverallDiscount(), invoice.getRoundOff(),
            invoice.getInvoiceTotal(), invoice.getTaxTreatment());

    // Absent means "leave it as it stands", so that adding the totals to a bill which never had
    // them does not require restating everything else on the header.
    if (request.getLineSubTotal() != null) invoice.setLineSubTotal(request.getLineSubTotal());
    if (request.getTaxTotal() != null) invoice.setTaxTotal(request.getTaxTotal());
    if (request.getShippingCharge() != null) {
      invoice.setShippingCharge(request.getShippingCharge());
    }
    if (request.getOtherCharges() != null) invoice.setOtherCharges(request.getOtherCharges());
    if (request.getOverallDiscount() != null) {
      invoice.setOverallDiscount(request.getOverallDiscount());
    }
    if (request.getRoundOff() != null) invoice.setRoundOff(request.getRoundOff());
    if (request.getInvoiceTotal() != null) invoice.setInvoiceTotal(request.getInvoiceTotal());
    if (request.getTaxTreatment() != null) invoice.setTaxTreatment(request.getTaxTreatment());

    validateAmendedHeader(invoice);

    invoice.setPreviousHeader(before);
    invoice.setAmendedAt(Instant.now());
    invoice.setAmendedByUserId(userId);
    invoice.setAmendmentReason(request.getReason().trim());

    recordResolvedTax(invoice);
    VendorPurchaseInvoice saved = vendorPurchaseInvoiceRepository.save(invoice);

    log.info("Invoice {} (shop {}) amended by {}: {} -- now reconciles as {}",
        saved.getInvoiceNo(), shopId, userId, saved.getAmendmentReason(),
        saved.getHeaderReconciliation());

    return getById(saved.getId(), shopId);
  }

  /** The same shape check registration applies, so an amendment cannot introduce what it rejects. */
  private void validateAmendedHeader(VendorPurchaseInvoice invoice) {
    Set<String> errors = new LinkedHashSet<>();
    rejectIfNegative(errors, "Line subtotal", invoice.getLineSubTotal());
    rejectIfNegative(errors, "Tax total", invoice.getTaxTotal());
    rejectIfNegative(errors, "Invoice total", invoice.getInvoiceTotal());
    rejectIfNegative(errors, "Shipping charge", invoice.getShippingCharge());
    rejectIfNegative(errors, "Other charges", invoice.getOtherCharges());
    rejectIfNegative(errors, "Overall discount", invoice.getOverallDiscount());

    BigDecimal subTotal = invoice.getLineSubTotal();
    BigDecimal tax = invoice.getTaxTotal();
    if (subTotal != null && tax != null && subTotal.signum() > 0 && tax.compareTo(subTotal) > 0) {
      errors.add("Tax total (" + tax + ") cannot exceed the line subtotal (" + subTotal
          + ") -- the highest GST slab is 28%");
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
  }

  private void rejectIfNegative(Set<String> errors, String label, BigDecimal value) {
    if (value != null && value.signum() < 0) {
      errors.add(label + " cannot be negative");
    }
  }

  /**
   * Re-resolves the invoice's tax from its lines, exactly as registration does.
   *
   * <p>Non-fatal for the same reason it is there: an amendment that records the operator's
   * figures but cannot re-derive the analysis is still an improvement on the header it replaced,
   * and the report path resolves on read regardless.
   */
  private void recordResolvedTax(VendorPurchaseInvoice invoice) {
    try {
      Map<String, Pricing> pricingByInventoryId = new HashMap<>();
      for (VendorPurchaseInvoiceLine line : invoice.getLines()) {
        if (!StringUtils.hasText(line.getInventoryId())) continue;
        inventoryRepository.findById(line.getInventoryId())
            .filter(lot -> StringUtils.hasText(lot.getPricingId()))
            .flatMap(lot -> pricingRepository.findById(lot.getPricingId()))
            .ifPresent(pricing -> pricingByInventoryId.put(line.getInventoryId(), pricing));
      }

      PurchaseTaxBasis basis = PurchaseTaxBasisResolver.resolve(
          invoice, pricingByInventoryId::get, invoice.getTaxTreatment(), false);

      for (int i = 0; i < invoice.getLines().size() && i < basis.lines().size(); i++) {
        VendorPurchaseInvoiceLine line = invoice.getLines().get(i);
        PurchaseTaxBasis.Line resolved = basis.lines().get(i);
        line.setTaxableValue(resolved.taxable());
        line.setGstRatePct(resolved.ratePct());
        line.setCentralTax(resolved.centralTax());
        line.setStateTax(resolved.stateTax());
        line.setIntegratedTax(resolved.integratedTax());
        line.setTaxBasisSource(resolved.source().name());
      }
      invoice.setComputedLineSubTotal(basis.totalTaxable());
      invoice.setComputedTaxTotal(basis.totalTax());
      invoice.setHeaderReconciliation(basis.verdict().name());
    } catch (RuntimeException e) {
      log.error("Could not re-resolve tax basis for amended invoice {} (shop {})",
          invoice.getInvoiceNo(), invoice.getShopId(), e);
    }
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
    dto.setHeaderReconciliation(e.getHeaderReconciliation());
    dto.setComputedLineSubTotal(e.getComputedLineSubTotal());
    dto.setComputedTaxTotal(e.getComputedTaxTotal());
    dto.setTaxTreatment(e.getTaxTreatment() != null ? e.getTaxTreatment().name() : null);
    dto.setAmendedAt(e.getAmendedAt());
    dto.setAmendedByUserId(e.getAmendedByUserId());
    dto.setAmendmentReason(e.getAmendmentReason());
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
