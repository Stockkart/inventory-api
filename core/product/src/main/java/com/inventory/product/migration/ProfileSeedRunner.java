package com.inventory.product.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.product.domain.model.BusinessType;
import com.inventory.product.domain.repository.BusinessTypeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Slf4j
public class ProfileSeedRunner {

  private static final String PROFILE_RESOURCE_PATTERN = "classpath:profiles/*.json";

  @Autowired
  private BusinessTypeRepository businessTypeRepository;

  @Autowired
  private ObjectMapper objectMapper;

  @EventListener(ApplicationReadyEvent.class)
  public void seedProfiles() {
    try {
      Resource[] resources = new PathMatchingResourcePatternResolver()
          .getResources(PROFILE_RESOURCE_PATTERN);
      int upserted = 0;
      for (Resource resource : resources) {
        BusinessType profile = objectMapper.readValue(resource.getInputStream(), BusinessType.class);
        if (profile.getId() == null || profile.getId().isBlank()) {
          log.warn("Skipping profile seed with missing id: {}", resource.getFilename());
          continue;
        }
        if (profile.getRegisteredAt() == null) {
          profile.setRegisteredAt(Instant.now());
        }
        businessTypeRepository.save(profile);
        upserted++;
        log.info("Seeded business profile: {}", profile.getId());
      }
      if (upserted > 0) {
        log.info("Profile seed completed: {} profile(s)", upserted);
      }
    } catch (Exception e) {
      log.error("Profile seed failed: {}", e.getMessage(), e);
    }
  }
}
