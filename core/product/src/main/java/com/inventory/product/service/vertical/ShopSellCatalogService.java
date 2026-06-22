package com.inventory.product.service.vertical;

import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.menu.MenuSection;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.response.InventoryListResponse;
import com.inventory.product.rest.dto.response.InventorySummaryDto;
import com.inventory.product.rest.dto.response.SellCatalogResponse;
import com.inventory.product.rest.dto.response.ShopMenuResponse;
import com.inventory.product.service.InventoryService;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class ShopSellCatalogService {

  private final ShopMenuService shopMenuService;
  private final InventoryService inventoryService;
  private final ShopRepository shopRepository;
  private final ShopValidator shopValidator;
  private final UserShopMembershipService membershipService;

  public ShopSellCatalogService(
      ShopMenuService shopMenuService,
      InventoryService inventoryService,
      ShopRepository shopRepository,
      ShopValidator shopValidator,
      UserShopMembershipService membershipService) {
    this.shopMenuService = shopMenuService;
    this.inventoryService = inventoryService;
    this.shopRepository = shopRepository;
    this.shopValidator = shopValidator;
    this.membershipService = membershipService;
  }

  @Transactional(readOnly = true)
  public SellCatalogResponse getSellCatalog(String shopId, String userId, String query) {
    Shop shop = loadShopWithAccess(shopId, userId);
    ShopMenuResponse menu = shopMenuService.getShopMenu(shopId, userId);
    String q = StringUtils.hasText(query) ? query.trim() : null;

    menu.setSections(filterMenuSections(menu.getSections(), q));

    List<InventorySummaryDto> directStock = List.of();
    if ("cafe".equalsIgnoreCase(shop.getVerticalId())) {
      directStock = loadCafeDirectStock(shopId, q);
    }

    SellCatalogResponse response = new SellCatalogResponse();
    response.setMenu(menu);
    response.setDirectStock(directStock);
    return response;
  }

  private List<InventorySummaryDto> loadCafeDirectStock(String shopId, String query) {
    try {
      Map<String, String> filters = new LinkedHashMap<>();
      filters.put("sellDirect", "yes");
      InventoryListResponse list =
          inventoryService.search(shopId, query, filters, null, 200, null);
      return list.getData() != null ? list.getData() : List.of();
    } catch (Exception e) {
      log.warn("Failed to load cafe direct stock for shop {}: {}", shopId, e.getMessage());
      return List.of();
    }
  }

  private static List<MenuSection> filterMenuSections(List<MenuSection> sections, String query) {
    if (sections == null || sections.isEmpty()) {
      return List.of();
    }
    String lower = query != null ? query.toLowerCase() : null;
    List<MenuSection> out = new ArrayList<>();
    for (MenuSection section : sections) {
      if (section == null) {
        continue;
      }
      List<MenuItem> items = section.getItems();
      if (items == null || items.isEmpty()) {
        continue;
      }
      List<MenuItem> kept = new ArrayList<>();
      for (MenuItem item : items) {
        if (item == null || !StringUtils.hasText(item.getName())) {
          continue;
        }
        if (Boolean.FALSE.equals(item.getAvailable())) {
          continue;
        }
        if (lower != null && !item.getName().toLowerCase().contains(lower)) {
          continue;
        }
        kept.add(item);
      }
      if (kept.isEmpty()) {
        continue;
      }
      MenuSection copy = new MenuSection();
      copy.setId(section.getId());
      copy.setTitle(section.getTitle());
      copy.setSortOrder(section.getSortOrder());
      copy.setItems(kept);
      out.add(copy);
    }
    return out;
  }

  private Shop loadShopWithAccess(String shopId, String userId) {
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
    return shopRepository
        .findById(shopId)
        .orElseThrow(
            () ->
                new com.inventory.common.exception.ResourceNotFoundException(
                    "Shop", "shopId", shopId));
  }
}
