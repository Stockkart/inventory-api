package com.inventory.product.service.vertical;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.menu.MenuSection;
import com.inventory.pluginengine.menu.MenuSellMode;
import com.inventory.pluginengine.menu.MenuVerticalValidator;
import com.inventory.pluginengine.menu.ShopMenu;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopMenuDocument;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ShopMenuRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.UpsertShopMenuRequest;
import com.inventory.product.rest.dto.response.ShopMenuResponse;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class ShopMenuService {

  private final ShopMenuRepository shopMenuRepository;
  private final ShopRepository shopRepository;
  private final InventoryRepository inventoryRepository;
  private final PluginRegistry pluginRegistry;
  private final SchemaLoader schemaLoader;
  private final ShopValidator shopValidator;
  private final UserShopMembershipService membershipService;

  public ShopMenuService(
      ShopMenuRepository shopMenuRepository,
      ShopRepository shopRepository,
      InventoryRepository inventoryRepository,
      @Lazy PluginRegistry pluginRegistry,
      SchemaLoader schemaLoader,
      ShopValidator shopValidator,
      UserShopMembershipService membershipService) {
    this.shopMenuRepository = shopMenuRepository;
    this.shopRepository = shopRepository;
    this.inventoryRepository = inventoryRepository;
    this.pluginRegistry = pluginRegistry;
    this.schemaLoader = schemaLoader;
    this.shopValidator = shopValidator;
    this.membershipService = membershipService;
  }

  @Transactional(readOnly = true)
  public ShopMenuResponse getShopMenu(String shopId, String userId) {
    Shop shop = loadShopWithAccess(shopId, userId);
    return shopMenuRepository
        .findByShopIdAndVerticalId(shopId, shop.getVerticalId())
        .map(this::toResponse)
        .orElseGet(() -> emptyMenuResponse(shop));
  }

  @Transactional
  public ShopMenuResponse upsertShopMenu(
      String shopId, String userId, UpsertShopMenuRequest request) {
    if (request == null) {
      throw new ValidationException("Menu request is required");
    }
    Shop shop = loadShopWithAccess(shopId, userId);
    VerticalSchema schema =
        schemaLoader.load(shop.getVerticalId(), shop.getPluginVersion());

    ShopMenu menuPayload = new ShopMenu();
    menuPayload.setShopId(shopId);
    menuPayload.setVerticalId(shop.getVerticalId());
    menuPayload.setSections(normalizeSections(request.getSections()));

    resolveMenuValidator(shop.getVerticalId()).validate(menuPayload, schema, shopId);
    validateDirectInventoryLinks(shopId, menuPayload);

    ShopMenuDocument existing =
        shopMenuRepository
            .findByShopIdAndVerticalId(shopId, shop.getVerticalId())
            .orElse(null);

    if (existing != null
        && request.getRevision() != null
        && existing.getRevision() != null
        && !request.getRevision().equals(existing.getRevision())) {
      throw new ValidationException(
          "Menu revision conflict — reload the menu and try again (expected revision "
              + existing.getRevision()
              + ")");
    }

    ShopMenuDocument doc = existing != null ? existing : new ShopMenuDocument();
    if (doc.getId() == null) {
      doc.setId(UUID.randomUUID().toString());
      doc.setShopId(shopId);
      doc.setVerticalId(shop.getVerticalId());
      doc.setPluginVersion(shop.getPluginVersion());
      doc.setRevision(0);
    }
    doc.setSections(menuPayload.getSections());
    doc.setRevision((doc.getRevision() != null ? doc.getRevision() : 0) + 1);
    doc.setUpdatedAt(Instant.now());
    doc.setUpdatedBy(userId);

    ShopMenuDocument saved = shopMenuRepository.save(doc);
    log.info(
        "Saved shop menu for shop={} vertical={} revision={}",
        shopId,
        shop.getVerticalId(),
        saved.getRevision());
    return toResponse(saved);
  }

  @Transactional(readOnly = true)
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

  private Shop loadShopWithAccess(String shopId, String userId) {
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
    Shop shop =
        shopRepository
            .findById(shopId)
            .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));
    if (!StringUtils.hasText(shop.getVerticalId())) {
      throw new ValidationException("Shop has no vertical configured");
    }
    return shop;
  }

  private MenuVerticalValidator resolveMenuValidator(String verticalId) {
    return pluginRegistry
        .find(verticalId)
        .flatMap(VerticalPlugin::getMenuVerticalValidator)
        .orElseThrow(
            () ->
                new ValidationException(
                    "Menu management is not supported for vertical: " + verticalId));
  }

  private void validateDirectInventoryLinks(String shopId, ShopMenu menu) {
    if (menu.getSections() == null) {
      return;
    }
    for (MenuSection section : menu.getSections()) {
      if (section == null || section.getItems() == null) {
        continue;
      }
      for (MenuItem item : section.getItems()) {
        if (item == null || item.getSellMode() != MenuSellMode.direct) {
          continue;
        }
        String inventoryId = item.getInventoryId().trim();
        boolean exists =
            inventoryRepository
                .findById(inventoryId)
                .filter(inv -> shopId.equals(inv.getShopId()))
                .isPresent();
        if (!exists) {
          throw new ValidationException(
              "Menu item \""
                  + item.getName()
                  + "\" links to inventory that does not exist in this shop: "
                  + inventoryId);
        }
      }
    }
  }

  private static List<MenuSection> normalizeSections(List<MenuSection> sections) {
    if (sections == null) {
      return List.of();
    }
    List<MenuSection> out = new ArrayList<>();
    for (MenuSection section : sections) {
      if (section == null) {
        continue;
      }
      if (!StringUtils.hasText(section.getId())) {
        section.setId(UUID.randomUUID().toString());
      }
      if (section.getItems() != null) {
        for (MenuItem item : section.getItems()) {
          if (item != null && !StringUtils.hasText(item.getId())) {
            item.setId(UUID.randomUUID().toString());
          }
        }
      }
      out.add(section);
    }
    return out;
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

  private ShopMenuResponse emptyMenuResponse(Shop shop) {
    ShopMenuResponse response = new ShopMenuResponse();
    response.setShopId(shop.getShopId());
    response.setVerticalId(shop.getVerticalId());
    response.setPluginVersion(shop.getPluginVersion());
    response.setRevision(0);
    response.setSections(List.of());
    return response;
  }

  private ShopMenuResponse toResponse(ShopMenuDocument doc) {
    ShopMenuResponse response = new ShopMenuResponse();
    response.setId(doc.getId());
    response.setShopId(doc.getShopId());
    response.setVerticalId(doc.getVerticalId());
    response.setPluginVersion(doc.getPluginVersion());
    response.setRevision(doc.getRevision());
    response.setSections(doc.getSections() != null ? doc.getSections() : List.of());
    response.setUpdatedAt(doc.getUpdatedAt());
    response.setUpdatedBy(doc.getUpdatedBy());
    return response;
  }
}
