package com.inventory.product.migration;

import com.inventory.pluginengine.profile.BusinessProfile;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class ShopBusinessProfileBackfillRunner {

  @Autowired
  private ShopRepository shopRepository;

  @EventListener(ApplicationReadyEvent.class)
  public void backfillShops() {
    try {
      int updated = 0;
      for (Shop shop : shopRepository.findAll()) {
        if (StringUtils.hasText(shop.getBusinessProfileId())) {
          continue;
        }
        shop.setBusinessProfileId(BusinessProfile.DEFAULT_PROFILE_ID);
        if (!StringUtils.hasText(shop.getBusinessId())) {
          shop.setBusinessId(BusinessProfile.DEFAULT_PROFILE_ID);
        }
        shopRepository.save(shop);
        updated++;
      }
      if (updated > 0) {
        log.info("Shop businessProfileId backfill completed: {} shop(s)", updated);
      }
    } catch (Exception e) {
      log.error("Shop businessProfileId backfill failed: {}", e.getMessage(), e);
    }
  }
}
