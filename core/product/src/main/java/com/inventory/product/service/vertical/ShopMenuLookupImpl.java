package com.inventory.product.service.vertical;

import com.inventory.pluginengine.integration.ShopMenuLookup;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.menu.MenuSection;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopMenuRepository;
import com.inventory.product.domain.repository.ShopRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Read-only menu lookup for cart plugins. Uses repositories directly so cafe cart wiring does
 * not depend on {@link ShopMenuService} (which needs {@code PluginRegistry} and would cycle).
 */
@Component
public class ShopMenuLookupImpl implements ShopMenuLookup {

  private final ShopMenuRepository shopMenuRepository;
  private final ShopRepository shopRepository;

  public ShopMenuLookupImpl(
      ShopMenuRepository shopMenuRepository, ShopRepository shopRepository) {
    this.shopMenuRepository = shopMenuRepository;
    this.shopRepository = shopRepository;
  }

  @Override
  public Optional<MenuItem> findMenuItem(String shopId, String menuItemId) {
    if (!StringUtils.hasText(shopId) || !StringUtils.hasText(menuItemId)) {
      return Optional.empty();
    }
    Shop shop = shopRepository.findById(shopId).orElse(null);
    if (shop == null || !StringUtils.hasText(shop.getVerticalId())) {
      return Optional.empty();
    }
    return shopMenuRepository
        .findByShopIdAndVerticalId(shopId, shop.getVerticalId())
        .flatMap(doc -> findItemInSections(doc.getSections(), menuItemId.trim()));
  }

  private static Optional<MenuItem> findItemInSections(
      List<MenuSection> sections, String menuItemId) {
    if (sections == null) {
      return Optional.empty();
    }
    for (MenuSection section : sections) {
      if (section == null || section.getItems() == null) {
        continue;
      }
      for (MenuItem item : section.getItems()) {
        if (item != null && menuItemId.equals(item.getId())) {
          return Optional.of(item);
        }
      }
    }
    return Optional.empty();
  }
}
