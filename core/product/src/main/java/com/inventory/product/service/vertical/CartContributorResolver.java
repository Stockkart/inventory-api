package com.inventory.product.service.vertical;

import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.cart.CartBuildContext;
import com.inventory.pluginengine.cart.CartLineContributor;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import java.util.Optional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CartContributorResolver {

  private final ShopRepository shopRepository;
  private final PluginRegistry pluginRegistry;

  public CartContributorResolver(
      ShopRepository shopRepository, @Lazy PluginRegistry pluginRegistry) {
    this.shopRepository = shopRepository;
    this.pluginRegistry = pluginRegistry;
  }

  public Optional<CartLineContributor> resolveContributor(String shopId) {
    Shop shop = shopRepository.findById(shopId).orElse(null);
    if (shop == null || !StringUtils.hasText(shop.getVerticalId())) {
      return Optional.empty();
    }
    return pluginRegistry
        .find(shop.getVerticalId())
        .flatMap(VerticalPlugin::getCartLineContributor);
  }

  public CartBuildContext buildContext(String shopId) {
    Shop shop = shopRepository.findById(shopId).orElse(null);
    if (shop == null) {
      return CartBuildContext.builder().shopId(shopId).build();
    }
    return CartBuildContext.builder()
        .shopId(shopId)
        .verticalId(shop.getVerticalId())
        .pluginVersion(shop.getPluginVersion())
        .build();
  }
}
