package com.inventory.product.service.estimate;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.DocumentType;
import com.inventory.product.domain.model.enums.EstimateState;
import com.inventory.product.domain.model.enums.InventorySellRestriction;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.util.PurchaseItemRefs;
import com.inventory.product.utils.CheckoutUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Centralized sell-estimate / inventory sell-path guards.
 *
 * <p>Keeps lock, print, convert, and checkout rules in one place so controllers and services stay
 * thin and the policy stays extensible.
 */
@Component
@RequiredArgsConstructor
public class EstimateInventoryPolicy {

  private final InventoryRepository inventoryRepository;

  public void assertEditable(Purchase estimate) {
    if (estimate.getEstimateState() != EstimateState.OPEN) {
      throw new ValidationException(
          "Cannot modify estimate in state " + estimate.getEstimateState());
    }
  }

  public void assertPrintable(Purchase estimate) {
    EstimateState state = estimate.getEstimateState();
    if (state == EstimateState.OPEN) {
      throw new ValidationException("Lock the estimate before printing");
    }
    if (state == EstimateState.DISCARDED) {
      throw new ValidationException("Discarded estimates cannot be printed");
    }
  }

  public void assertLockable(Purchase estimate) {
    if (estimate.getEstimateState() != EstimateState.OPEN) {
      throw new ValidationException(
          "Only open estimates can be locked (state: " + estimate.getEstimateState() + ")");
    }
    if (estimate.getItems() == null || estimate.getItems().isEmpty()) {
      throw new ValidationException("Cannot lock an empty estimate");
    }
  }

  /**
   * Convert is allowed from OPEN or LOCKED. Rejected when any line is BASIC / estimate-only stock
   * (those estimates finalize with lock + print only).
   */
  public void assertConvertibleToSale(Purchase estimate) {
    EstimateState state = estimate.getEstimateState();
    if (state != EstimateState.OPEN && state != EstimateState.LOCKED) {
      throw new ValidationException(
          "Only open or locked estimates can be converted (state: " + state + ")");
    }
    if (estimate.getItems() == null || estimate.getItems().isEmpty()) {
      throw new ValidationException("Cannot convert an empty estimate");
    }
    if (containsEstimateOnlyLines(estimate)) {
      throw new ValidationException(
          "Estimate-only (BASIC) stock cannot convert to invoice. Lock and print instead.");
    }
  }

  public boolean containsEstimateOnlyLines(Purchase purchase) {
    if (purchase.getItems() == null) {
      return false;
    }
    Set<String> lotIds = new HashSet<>();
    for (PurchaseItem item : purchase.getItems()) {
      if ("menu".equalsIgnoreCase(item.getSellMode())) {
        continue;
      }
      PurchaseItemRefs.normalize(item);
      String lotId = PurchaseItemRefs.stockLotId(item);
      if (StringUtils.hasText(lotId)) {
        lotIds.add(lotId.trim());
      } else if (CheckoutUtils.normalizeBillingMode(item.getBillingMode()) == BillingMode.BASIC) {
        return true;
      }
    }
    if (lotIds.isEmpty()) {
      return CheckoutUtils.normalizeBillingMode(purchase.getBillingMode()) == BillingMode.BASIC;
    }
    List<Inventory> lots = inventoryRepository.findByIdIn(List.copyOf(lotIds));
    for (Inventory lot : lots) {
      if (isEstimateOnly(lot)) {
        return true;
      }
    }
    return false;
  }

  public static boolean isEstimateOnly(Inventory inventory) {
    if (inventory == null) {
      return false;
    }
    if (inventory.getSellRestriction() == InventorySellRestriction.ESTIMATE_ONLY) {
      return true;
    }
    // Legacy BASIC lots without sellRestriction are treat as estimate-only after backfill;
    // until then, BASIC alone is the signal.
    return CheckoutUtils.resolveInventoryBillingMode(inventory) == BillingMode.BASIC
        && inventory.getSellRestriction() == null;
  }

  /** Reject completing a SALE cart that uses BASIC / estimate-only inventory. */
  public void assertSaleCheckoutAllowed(Purchase purchase) {
    if (purchase.getDocumentType() == DocumentType.ESTIMATE) {
      throw new ValidationException(
          "Estimates cannot be checked out. Convert the estimate to an invoice first.");
    }
    BillingMode mode = CheckoutUtils.normalizeBillingMode(purchase.getBillingMode());
    if (mode == BillingMode.BASIC || containsEstimateOnlyLines(purchase)) {
      throw new ValidationException(
          "BASIC / estimate-only stock cannot be invoiced. Complete via Sell Estimate (lock + print).");
    }
  }

  public static InventorySellRestriction resolveSellRestriction(Inventory inventory) {
    if (inventory == null || inventory.getSellRestriction() == null) {
      return InventorySellRestriction.ANY;
    }
    return inventory.getSellRestriction();
  }
}
