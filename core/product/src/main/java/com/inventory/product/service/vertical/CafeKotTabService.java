package com.inventory.product.service.vertical;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.kot.CafeKotPort;
import com.inventory.pluginengine.kot.CafeKotTab;
import com.inventory.pluginengine.kot.CafeKotTicket;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.repository.PurchaseRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Cafe KOT tab composition and flush, orchestrated from {@code core/product} against the cafe
 * plugin's {@link CafeKotPort} — the same arrangement {@link CafeKotService} uses for a ticket's
 * document.
 *
 * <p>{@code plugins/cafe} does not depend on {@code core/product} and cannot see {@link Purchase},
 * so the one check that needs the bill's own {@code userId} — refusing a flush onto another
 * cashier's open bill — is enforced here, the same way {@code QuotationService.resolveTargetCart}
 * refuses it for the Sell screen's own "add to this cart" choice.
 */
@Service
public class CafeKotTabService {

  private static final String VERTICAL_ID = "cafe";

  private final PluginRegistry pluginRegistry;
  private final PurchaseRepository purchaseRepository;

  public CafeKotTabService(PluginRegistry pluginRegistry, PurchaseRepository purchaseRepository) {
    this.pluginRegistry = pluginRegistry;
    this.purchaseRepository = purchaseRepository;
  }

  private CafeKotPort port() {
    return pluginRegistry
        .require(VERTICAL_ID)
        .getCafeKotPort()
        .orElseThrow(
            () -> new ValidationException("This shop's vertical does not support kitchen tickets"));
  }

  public List<CafeKotTab> list(String shopId, String userId) {
    return port().listTabs(shopId, userId);
  }

  public CafeKotTab open(String shopId, String userId) {
    return port().openTab(shopId, userId);
  }

  public CafeKotTab addOrUpdateLine(
      String shopId,
      String userId,
      String tabId,
      String lineRef,
      String sellableRef,
      Integer quantity,
      String note) {
    if (quantity == null || quantity <= 0) {
      throw new ValidationException("Quantity must be positive");
    }
    if (!StringUtils.hasText(lineRef) && !StringUtils.hasText(sellableRef)) {
      throw new ValidationException("sellableRef is required to add a new tab line");
    }
    return port().upsertTabLine(shopId, userId, tabId, lineRef, sellableRef, quantity, note);
  }

  public CafeKotTab removeLine(String shopId, String userId, String tabId, String lineRef) {
    return port().removeTabLine(shopId, userId, tabId, lineRef);
  }

  public void close(String shopId, String userId, String tabId) {
    port().closeTab(shopId, userId, tabId);
  }

  /**
   * Flushes a tab, refusing a {@code targetPurchaseId} that names another cashier's bill before
   * the plugin — which can only see the bill as a raw shop-scoped document — is ever asked to
   * touch it.
   */
  public List<CafeKotTicket> flush(
      String shopId, String userId, String tabId, String targetPurchaseId, String idempotencyKey) {
    if (StringUtils.hasText(targetPurchaseId)) {
      requireOwnedOpenBill(shopId, userId, targetPurchaseId);
    }
    return port().flush(shopId, userId, tabId, targetPurchaseId, idempotencyKey);
  }

  /** Mirrors {@code QuotationService.resolveTargetCart}'s ownership check exactly. */
  private void requireOwnedOpenBill(String shopId, String userId, String targetPurchaseId) {
    Purchase purchase =
        purchaseRepository
            .findById(targetPurchaseId)
            .orElseThrow(() -> new ResourceNotFoundException("Purchase", "id", targetPurchaseId));
    if (!shopId.equals(purchase.getShopId())) {
      throw new ValidationException("Bill does not belong to the authenticated shop");
    }
    if (!userId.equals(purchase.getUserId())) {
      throw new ValidationException("Bill does not belong to the authenticated user");
    }
  }
}
