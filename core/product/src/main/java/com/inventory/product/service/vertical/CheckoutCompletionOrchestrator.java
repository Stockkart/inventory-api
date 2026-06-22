package com.inventory.product.service.vertical;

import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.cart.CartSellMode;
import com.inventory.product.util.PurchaseItemRefs;
import com.inventory.pluginengine.cart.CheckoutCompletionContext;
import com.inventory.pluginengine.cart.CheckoutCompletionHandler;
import com.inventory.pluginengine.cart.CheckoutCompletionResult;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.util.PurchaseItemRefs;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CheckoutCompletionOrchestrator {

  private final ShopRepository shopRepository;
  private final PluginRegistry pluginRegistry;

  public CheckoutCompletionOrchestrator(
      ShopRepository shopRepository, PluginRegistry pluginRegistry) {
    this.shopRepository = shopRepository;
    this.pluginRegistry = pluginRegistry;
  }

  public Optional<CheckoutCompletionResult> onPurchaseCompleted(Purchase purchase) {
    if (purchase == null || !StringUtils.hasText(purchase.getShopId())) {
      return Optional.empty();
    }
    Shop shop = shopRepository.findById(purchase.getShopId()).orElse(null);
    if (shop == null || !StringUtils.hasText(shop.getVerticalId())) {
      return Optional.empty();
    }
    Optional<CheckoutCompletionHandler> handler =
        pluginRegistry.find(shop.getVerticalId()).flatMap(VerticalPlugin::getCheckoutCompletionHandler);
    if (handler.isEmpty()) {
      return Optional.empty();
    }
    CheckoutCompletionContext context =
        CheckoutCompletionContext.builder()
            .shopId(purchase.getShopId())
            .purchaseId(purchase.getId())
            .verticalId(shop.getVerticalId())
            .grandTotal(purchase.getGrandTotal())
            .lines(mapLines(purchase.getItems()))
            .build();
    return Optional.of(handler.get().onPurchaseCompleted(context));
  }

  private static List<CheckoutCompletionContext.CompletedCartLine> mapLines(
      List<PurchaseItem> items) {
    if (items == null) {
      return List.of();
    }
    return items.stream()
        .map(
            item -> {
              PurchaseItemRefs.normalize(item);
              return CheckoutCompletionContext.CompletedCartLine.builder()
                    .sellableRef(item.getSellableRef())
                    .stockRef(item.getStockRef())
                    .sellMode(parseSellMode(item.getSellMode()))
                    .name(item.getName())
                    .baseQuantity(item.getBaseQuantity())
                    .quantity(item.getQuantity())
                    .unitFactor(item.getUnitFactor())
                    .costPrice(item.getCostPrice())
                    .build();
            })
        .collect(Collectors.toList());
  }

  private static CartSellMode parseSellMode(String sellMode) {
    if (!StringUtils.hasText(sellMode)) {
      return CartSellMode.SKU;
    }
    return switch (sellMode.trim().toLowerCase()) {
      case "menu" -> CartSellMode.MENU;
      case "direct" -> CartSellMode.DIRECT;
      default -> CartSellMode.SKU;
    };
  }
}
