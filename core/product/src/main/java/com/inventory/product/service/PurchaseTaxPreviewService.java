package com.inventory.product.service;

import com.inventory.common.constants.PurchaseTaxTreatment;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.mapper.InventoryMapper;
import com.inventory.product.rest.dto.request.CreateInventoryItemRequest;
import com.inventory.product.rest.dto.request.PurchaseTaxPreviewRequest;
import com.inventory.product.rest.dto.response.PurchaseTaxPreviewResponse;
import com.inventory.product.utils.PurchaseTaxBasis;
import com.inventory.product.utils.PurchaseTaxBasisResolver;
import com.inventory.product.validation.VendorPurchaseInvoiceValidator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Works out what the bill on the stock-in screen comes to, before anything is saved.
 *
 * <p>The screen used to compute GST, schemes and totals in the browser, a second copy of the rules
 * stock-in applies, and the two disagreed (a free-unit scheme reduced the taxable value on screen
 * but not on the books). This runs the stock-in rules instead: the item rows become invoice lines
 * and purchase pricing through the same mapper stock-in uses, the tax treatment comes from the same
 * resolver, and the totals from {@link PurchaseTaxBasisResolver} and {@link PurchaseTaxRecorder},
 * which is how stock-in works them out.
 */
@Service
@RequiredArgsConstructor
public class PurchaseTaxPreviewService {

  private final InventoryMapper inventoryMapper;
  private final PurchaseTaxTreatmentResolver purchaseTaxTreatmentResolver;
  private final VendorPurchaseInvoiceValidator vendorPurchaseInvoiceValidator;

  public PurchaseTaxPreviewResponse preview(PurchaseTaxPreviewRequest request) {
    vendorPurchaseInvoiceValidator.validatePreview(request);

    VendorPurchaseInvoice invoice = new VendorPurchaseInvoice();
    List<VendorPurchaseInvoiceLine> lines = new ArrayList<>();
    Map<String, Pricing> pricingByRow = new HashMap<>();
    int totalQuantity = 0;
    for (CreateInventoryItemRequest item : request.getItems()) {
      String rowId = "row-" + lines.size();
      VendorPurchaseInvoiceLine line = inventoryMapper.toInvoiceLine(item);
      line.setLineIndex(lines.size());
      line.setInventoryId(rowId);
      lines.add(line);
      pricingByRow.put(rowId, inventoryMapper.toPurchasePricing(item));
      if (item.getCount() != null && item.getCount() > 0) {
        totalQuantity += item.getCount();
      }
    }
    invoice.setLines(lines);
    invoice.setShippingCharge(request.getShippingCharge());
    invoice.setOtherCharges(request.getOtherCharges());
    invoice.setOverallDiscount(request.getOverallDiscount());
    invoice.setRoundOff(request.getRoundOff());

    PurchaseTaxTreatmentResolver.Resolved resolved = purchaseTaxTreatmentResolver.resolve(
        request.getTaxTreatment(), request.getVendorId(), request.getItems());
    PurchaseTaxTreatment treatment = resolved.treatment();
    // Intra-state, as stock-in records it until a purchase carries a place of supply.
    PurchaseTaxBasis basis =
        PurchaseTaxBasisResolver.resolve(invoice, pricingByRow::get, treatment, false);
    // The same totals stock-in will store.
    PurchaseTaxRecorder.apply(invoice, basis);

    PurchaseTaxPreviewResponse out = new PurchaseTaxPreviewResponse();
    out.setTaxTreatment(treatment);
    out.setTaxTreatmentSource(resolved.source().name());
    out.setLineSubTotal(money(invoice.getLineSubTotal()));
    out.setTaxTotal(money(invoice.getTaxTotal()));
    out.setItemsTotal(money(invoice.getLineSubTotal().add(invoice.getTaxTotal())));
    out.setInvoiceTotal(money(invoice.getInvoiceTotal()));
    out.setProductCount(lines.size());
    out.setTotalQuantity(totalQuantity);
    out.setLines(basis.lines().stream().map(inventoryMapper::toPreviewLine).toList());
    return out;
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }
}
