package com.inventory.app.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.pluginengine.profile.BusinessProfile;
import com.inventory.product.domain.model.BusinessType;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.BusinessTypeRepository;
import com.inventory.product.domain.repository.ShopRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

/**
 * Seeds business profiles from classpath JSON and backfills shops with default profile id.
 */
@Component
@Slf4j
public class ProfileSeedRunner implements ApplicationRunner {

  private final BusinessTypeRepository businessTypeRepository;
  private final ShopRepository shopRepository;
  private final ObjectMapper objectMapper;

  public ProfileSeedRunner(
      BusinessTypeRepository businessTypeRepository,
      ShopRepository shopRepository,
      ObjectMapper objectMapper) {
    this.businessTypeRepository = businessTypeRepository;
    this.shopRepository = shopRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    Resource[] resources = resolver.getResources("classpath:profiles/*.json");
    for (Resource resource : resources) {
      BusinessType seed = objectMapper.readValue(resource.getInputStream(), BusinessType.class);
      upsertProfile(seed);
    }
    backfillShops();
  }

  private void upsertProfile(BusinessType seed) {
    if (seed == null || !StringUtils.hasText(seed.getId())) {
      return;
    }
    BusinessType existing = businessTypeRepository.findById(seed.getId()).orElse(null);
    int seedVersion = seed.getVersion() != null ? seed.getVersion() : 1;
    if (existing != null) {
      int existingVersion = existing.getVersion() != null ? existing.getVersion() : 0;
      if (existingVersion >= seedVersion) {
        log.debug("Profile {} already at version {}, skip seed", seed.getId(), existingVersion);
        return;
      }
    }
    if (seed.getRegisteredAt() == null) {
      seed.setRegisteredAt(Instant.now());
    }
    businessTypeRepository.save(seed);
    log.info("Seeded business profile: {} (version {})", seed.getId(), seedVersion);
  }

  private void backfillShops() {
    List<Shop> shops = shopRepository.findAll();
    int updated = 0;
    for (Shop shop : shops) {
      if (!StringUtils.hasText(shop.getBusinessProfileId())) {
        shop.setBusinessProfileId(BusinessProfile.DEFAULT_PROFILE_ID);
        shopRepository.save(shop);
        updated++;
      }
    }
    if (updated > 0) {
      log.info("Backfilled businessProfileId=pharmacy on {} shop(s)", updated);
    }
  }
}
