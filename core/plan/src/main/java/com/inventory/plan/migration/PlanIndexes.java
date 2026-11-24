package com.inventory.plan.migration;

import com.inventory.plan.domain.model.Plan;
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
 * Plan catalogue indexes. Auto index creation is off, so they are ensured on startup. Catalogue
 * rows themselves are managed from the admin app.
 */
@Component
@Slf4j
public class PlanIndexes {

  @Autowired
  private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(20)
  public void ensureIndexes() {
    try {
      mongoTemplate.indexOps(Plan.class)
          .ensureIndex(new Index().on("code", Sort.Direction.ASC).unique().sparse());
    } catch (RuntimeException e) {
      log.error("Could not ensure the unique code index on plans (duplicate plan codes?): {}", e.getMessage());
    }
  }
}
