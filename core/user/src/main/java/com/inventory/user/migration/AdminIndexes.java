package com.inventory.user.migration;

import com.inventory.user.domain.model.AdminSession;
import com.inventory.user.domain.model.AdminUser;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Admin account indexes. Auto index creation is off, so they are ensured on startup. */
@Component
public class AdminIndexes {

  @Autowired
  private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(38)
  public void ensureIndexes() {
    mongoTemplate.indexOps(AdminUser.class)
        .ensureIndex(new Index().on("email", Sort.Direction.ASC).unique().named("email_unique"));

    var sessions = mongoTemplate.indexOps(AdminSession.class);
    sessions.ensureIndex(new Index().on("tokenHash", Sort.Direction.ASC).unique().named("tokenHash_unique"));
    sessions.ensureIndex(new Index().on("adminId", Sort.Direction.ASC).named("adminId"));
    sessions.ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO)
        .named("expiresAt_ttl"));
  }
}
