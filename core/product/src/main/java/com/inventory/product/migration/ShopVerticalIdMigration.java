package com.inventory.product.migration;

import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.service.vertical.VerticalCatalogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Backfills {@code Shop.verticalId} and {@code Shop.pluginVersion} for legacy shops.
 * Idempotent — safe to run on every startup until all shops are tagged.
 */
@Component
@Slf4j
public class ShopVerticalIdMigration {

  @Autowired private ShopRepository shopRepository;
  @Autowired private VerticalCatalogService verticalCatalogService;

  @EventListener(ApplicationReadyEvent.class)
  @Order(20)
  public void backfillLegacyShops() {
    try {
      VerticalCatalogService.VerticalPin legacyVertical =
          verticalCatalogService.resolveFirstActiveVertical();
      int updated = 0;
      for (Shop shop : shopRepository.findAll()) {
        if (StringUtils.hasText(shop.getVerticalId())) {
          continue;
        }
        shop.setVerticalId(legacyVertical.verticalId());
        shop.setPluginVersion(legacyVertical.pluginVersion());
        shopRepository.save(shop);
        updated++;
      }
      if (updated > 0) {
        log.info(
            "ShopVerticalIdMigration: tagged {} legacy shop(s) with verticalId={} pluginVersion={}",
            updated,
            legacyVertical.verticalId(),
            legacyVertical.pluginVersion());
      }
    } catch (Exception e) {
      log.error("ShopVerticalIdMigration failed: {}", e.getMessage(), e);
    }
  }
}
