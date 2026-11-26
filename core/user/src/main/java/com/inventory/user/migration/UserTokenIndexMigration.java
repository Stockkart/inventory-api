package com.inventory.user.migration;

import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.model.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Idempotent index runner for {@code users}:
 *
 * <ul>
 *   <li>{@code tokens_accessToken} {@code {tokens.accessToken: 1}}: every authenticated request
 *       that misses {@code AuthCache} finds its user by token ({@code
 *       UserAccountRepository.findByAccessToken}); without it that read scans every user.
 *   <li>{@code worker_shopId} partial {@code {shopId: 1}} where {@code role = WORKER} (worker-app
 *       R2.5, design DD1): the Users page's "Workers" group and {@code WorkerExitJob}'s release
 *       read workers by shop; the partial filter keeps staff and legacy owners out of it.
 * </ul>
 *
 * Automatic index creation is off, so the {@code @CompoundIndex} on {@link UserAccount} does
 * nothing by itself. Each index is ensured on its own, so a failure of one still leaves the other
 * in place.
 */
@Component
@Slf4j
public class UserTokenIndexMigration {

  static final String INDEX = "tokens_accessToken";
  static final String WORKER_SHOP_INDEX = "worker_shopId";

  @Autowired private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(8)
  public void run() {
    ensure(INDEX, new Index().on("tokens.accessToken", Sort.Direction.ASC).named(INDEX));
    ensure(WORKER_SHOP_INDEX, new Index()
        .on("shopId", Sort.Direction.ASC)
        .named(WORKER_SHOP_INDEX)
        .partial(PartialIndexFilter.of(Criteria.where("role").is(UserRole.WORKER.name()))));
  }

  private void ensure(String name, Index index) {
    try {
      mongoTemplate.indexOps(UserAccount.class).ensureIndex(index);
      log.info("[user-token-index] ensured {}", name);
    } catch (RuntimeException e) {
      log.error("[user-token-index] failed to ensure {}: {}", name, e.getMessage(), e);
    }
  }
}
