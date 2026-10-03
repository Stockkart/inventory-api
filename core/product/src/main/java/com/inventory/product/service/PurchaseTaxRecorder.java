package com.inventory.product.service;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.utils.PurchaseTaxBasis;
import com.inventory.product.utils.PurchaseTaxBasisResolver;
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
 * Resolves what a supplier bill's lines are worth for tax and records it on the invoice.
 *
 * <p>Shared by stock-in and by a header correction, so both write the same second opinion beside
 * the stated header. The stated header is never changed here; what is written is the resolved
 * taxable value and tax per line, the totals, and the verdict saying how far the two agree.
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
   * Resolves the invoice's lines and records the result on it (the caller saves).
   *
   * <p>Never fatal. Stock already exists by the time this runs, and an invoice that records its
   * goods but not its tax analysis is recoverable; one that fails half way through leaves the shop
   * with lots it cannot see. On failure the invoice keeps the header it was given and the report
   * path resolves it on read, as it does for every older document.
   */
  public void record(VendorPurchaseInvoice invoice) {
    try {
      Map<String, Pricing> pricing = pricingByInventoryId(invoice.getLines());
      // Interstate is not decided here. It turns on the supplier's state against the shop's, and
      // the tax heads are a property of the return rather than of the purchase, so the split is
      // left to the aggregator that knows both ends. The taxable value and rate stored here do
      // not change either way.
      PurchaseTaxBasis basis = PurchaseTaxBasisResolver.resolve(
          invoice, pricing::get, invoice.getTaxTreatment(), false);
      apply(invoice, basis);

      if (basis.verdict() != PurchaseTaxBasis.Verdict.OK) {
        log.warn("Invoice {} for shop {} recorded as {}: stated subtotal {}, tax {}; "
                + "lines resolve to {}, tax {}",
            invoice.getInvoiceNo(), invoice.getShopId(), basis.verdict(),
            invoice.getLineSubTotal(), invoice.getTaxTotal(),
            basis.totalTaxable(), basis.totalTax());
      }
    } catch (RuntimeException e) {
      log.error("Could not resolve tax basis for invoice {} (shop {}); "
              + "the invoice keeps its stated header and will be resolved on read",
          invoice.getInvoiceNo(), invoice.getShopId(), e);
    }
  }

  static void apply(VendorPurchaseInvoice invoice, PurchaseTaxBasis basis) {
    List<VendorPurchaseInvoiceLine> lines = invoice.getLines();
    for (int i = 0; i < lines.size() && i < basis.lines().size(); i++) {
      VendorPurchaseInvoiceLine line = lines.get(i);
      PurchaseTaxBasis.Line resolved = basis.lines().get(i);
      line.setTaxableValue(resolved.taxable());
      line.setCentralTax(resolved.centralTax());
      line.setStateTax(resolved.stateTax());
      line.setIntegratedTax(resolved.integratedTax());
      line.setTaxBasisSource(resolved.source().name());
    }
    invoice.setComputedLineSubTotal(basis.totalTaxable());
    invoice.setComputedTaxTotal(basis.totalTax());
    invoice.setHeaderReconciliation(basis.verdict().name());
  }
}
