package com.inventory.user.migration;

import com.mongodb.client.result.UpdateResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Shop users no longer carry platform roles; admins live in {@code admin_users}. Idempotent. */
@Component
@Slf4j
public class PlatformRolesCleanup {

  static final String USERS_COLLECTION = "users";

  @Autowired
  private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(41)
  public void onStartup() {
    try {
      UpdateResult result = mongoTemplate.updateMulti(
          new Query(Criteria.where("platformRoles").exists(true)),
          new Update().unset("platformRoles"),
          USERS_COLLECTION);
      if (result.getModifiedCount() > 0) {
        log.info("Removed platformRoles from {} shop users", result.getModifiedCount());
      }
    } catch (RuntimeException e) {
      log.error("platformRoles cleanup failed: {}", e.getMessage(), e);
    }
  }
}
