package com.inventory.user.migration;

import com.inventory.user.domain.model.UserAccount;
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
 * Idempotent: ensures {@code tokens_accessToken} {@code {tokens.accessToken: 1}} on {@code users}.
 * Every authenticated request that misses {@code AuthCache} finds its user by token
 * ({@code UserAccountRepository.findByAccessToken}); without this index that read scans every
 * user. Automatic index creation is off, so the {@code @CompoundIndex} on {@link UserAccount} does
 * nothing by itself.
 */
@Component
@Slf4j
public class UserTokenIndexMigration {

  static final String INDEX = "tokens_accessToken";

  @Autowired private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(8)
  public void run() {
    try {
      mongoTemplate.indexOps(UserAccount.class).ensureIndex(
          new Index().on("tokens.accessToken", Sort.Direction.ASC).named(INDEX));
      log.info("[user-token-index] ensured {}", INDEX);
    } catch (RuntimeException e) {
      log.error("[user-token-index] failed to ensure {}: {}", INDEX, e.getMessage(), e);
    }
  }
}
