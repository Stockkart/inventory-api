package com.inventory.product.service;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.utils.PurchaseTaxBasis;
import com.inventory.product.utils.PurchaseTaxBasisResolver;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Works out a supplier bill's totals from its lines and writes them on the invoice.
 *
 * <p>The line subtotal, tax total and invoice total are not typed by the operator; they are what
 * the lines come to. Shared by stock-in and by a header correction, so both arrive at the same
 * figures the same way.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseTaxRecorder {

  private final InventoryRepository inventoryRepository;
  private final PricingRepository pricingRepository;

  /**
   * The pricing behind each line's lot, keyed by inventory id, in two queries whatever the number
   * of lines. A line whose lot or pricing is missing is simply absent from the map.
   */
  public Map<String, Pricing> pricingByInventoryId(List<VendorPurchaseInvoiceLine> lines) {
    Set<String> inventoryIds = new LinkedHashSet<>();
    if (lines != null) {
      for (VendorPurchaseInvoiceLine line : lines) {
        if (StringUtils.hasText(line.getInventoryId())) {
          inventoryIds.add(line.getInventoryId());
        }
      }
    }
    if (inventoryIds.isEmpty()) {
      return Map.of();
    }

    Map<String, String> pricingIdByInventoryId = new HashMap<>();
    for (Inventory lot : inventoryRepository.findAllById(inventoryIds)) {
      if (StringUtils.hasText(lot.getPricingId())) {
        pricingIdByInventoryId.put(lot.getId(), lot.getPricingId());
      }
    }
    Map<String, Pricing> pricingById = new HashMap<>();
    for (Pricing pricing : pricingRepository.findAllById(pricingIdByInventoryId.values())) {
      pricingById.put(pricing.getId(), pricing);
    }

    Map<String, Pricing> out = new HashMap<>();
    pricingIdByInventoryId.forEach((inventoryId, pricingId) -> {
      Pricing pricing = pricingById.get(pricingId);
      if (pricing != null) {
        out.put(inventoryId, pricing);
      }
    });
    return out;
  }

  /**
   * Works out the invoice's totals from its lines and writes them on it (the caller saves).
   *
   * <p>Never fatal. Stock already exists by the time this runs, and an invoice that records its
   * goods but not its totals is recoverable; one that fails half way through leaves the shop with
   * lots it cannot see. On failure the totals stay empty and the reports resolve the lines on read.
   */
  public void record(VendorPurchaseInvoice invoice) {
    try {
      Map<String, Pricing> pricing = pricingByInventoryId(invoice.getLines());
      // Interstate is not decided here. It turns on the supplier's state against the shop's, and
      // the tax heads are a property of the return rather than of the purchase, so the split is
      // left to the aggregator that knows both ends. The totals do not change either way.
      PurchaseTaxBasis basis = PurchaseTaxBasisResolver.resolve(
          invoice, pricing::get, invoice.getTaxTreatment(), false);
      apply(invoice, basis);
    } catch (RuntimeException e) {
      log.error("Could not work out the totals of invoice {} (shop {}); "
              + "the reports will resolve its lines on read",
          invoice.getInvoiceNo(), invoice.getShopId(), e);
    }
  }

  /**
   * Writes the lines' tax and the invoice totals. The line subtotal is before the bill-level
   * discount, which the journal takes off it; the invoice total adds the charges and round-off.
   */
  static void apply(VendorPurchaseInvoice invoice, PurchaseTaxBasis basis) {
    List<VendorPurchaseInvoiceLine> lines = invoice.getLines();
    for (int i = 0; i < lines.size() && i < basis.lines().size(); i++) {
      VendorPurchaseInvoiceLine line = lines.get(i);
      PurchaseTaxBasis.Line resolved = basis.lines().get(i);
      line.setTaxableValue(resolved.taxable());
      line.setCentralTax(resolved.centralTax());
      line.setStateTax(resolved.stateTax());
      line.setIntegratedTax(resolved.integratedTax());
    }
    BigDecimal taxable = basis.totalTaxable();
    BigDecimal tax = basis.totalTax();
    invoice.setLineSubTotal(taxable.add(basis.overallDiscount()));
    invoice.setTaxTotal(tax);
    invoice.setInvoiceTotal(taxable
        .add(tax)
        .add(nz(invoice.getShippingCharge()))
        .add(nz(invoice.getOtherCharges()))
        .add(nz(invoice.getRoundOff())));
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
