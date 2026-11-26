package com.inventory.plan.migration;

import com.inventory.plan.domain.model.CampaignTheme;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Campaign theme indexes. Auto index creation is off, so they are ensured on startup. Theme rows
 * themselves are written directly in the database.
 */
@Component
@Slf4j
public class CampaignThemeIndexes {

  @Autowired
  private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(27)
  public void ensureIndexes() {
    try {
      mongoTemplate.indexOps(CampaignTheme.class)
          .ensureIndex(new Index().on("code", Sort.Direction.ASC).unique().named("code_unique"));
    } catch (RuntimeException e) {
      log.error("Could not ensure the unique code index on campaign_themes (duplicate codes?): {}", e.getMessage());
    }
  }
}
