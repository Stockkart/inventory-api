package com.inventory.user.service;

import com.inventory.user.domain.model.UserAccount;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes expired login tokens from every user, once a night.
 *
 * <p>A token is added at each login and only removed on logout from that session, so tokens of
 * sessions that simply lapsed (7 days) pile up in the user document, which every request loads.
 * {@link UserAccount#addToken} already drops a user's expired tokens when they log in; this covers
 * users who do not log in again. Expired tokens are already refused by
 * {@link TokenValidationService}, so removing them changes nothing for anyone.
 *
 * <p>One {@code updateMany} with {@code $pull}; idempotent, so it is safe if two instances run it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredTokenCleanupJob {

  private final MongoTemplate mongoTemplate;

  @Value("${auth.token-cleanup.enabled:true}")
  private boolean enabled;

  @Scheduled(cron = "${auth.token-cleanup.cron:0 30 3 * * *}", zone = "${auth.token-cleanup.zone:Asia/Kolkata}")
  public void run() {
    if (!enabled) {
      return;
    }
    try {
      long users = removeExpiredTokens(Instant.now());
      log.info("Expired token cleanup: {} users had expired tokens removed", users);
    } catch (RuntimeException e) {
      log.warn("Expired token cleanup failed: {}", e.getMessage(), e);
    }
  }

  /** Pulls tokens that expired before {@code cutoff}; returns how many users changed. */
  public long removeExpiredTokens(Instant cutoff) {
    Date before = Date.from(cutoff);
    Query users = Query.query(Criteria.where("tokens.expiresAt").lt(before));
    Update pull = new Update().pull("tokens", new Document("expiresAt", new Document("$lt", before)));
    UpdateResult result = mongoTemplate.updateMulti(users, pull, UserAccount.class);
    return result.getModifiedCount();
  }
}
