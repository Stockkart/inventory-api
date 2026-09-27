package com.inventory.product.migration;

import com.inventory.plan.utils.ReferralCodes;
import com.inventory.product.domain.model.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Gives every shop without one a referral code, and ensures the unique index. Each write is
 * conditional on the shop still having no code, so concurrent instances never overwrite each other.
 */
@Slf4j
@Component
public class ShopReferralCodeBackfill {

  private static final int BATCH = 500;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Value("${referral.code-backfill.enabled:true}")
  private boolean enabled;

  @EventListener(ApplicationReadyEvent.class)
  @Order(24)
  public void run() {
    mongoTemplate.indexOps(Shop.class).ensureIndex(new Index()
        .on("referralCode", Sort.Direction.ASC)
        .unique()
        .partial(PartialIndexFilter.of(Criteria.where("referralCode").exists(true)))
        .named("referralCode_unique"));
    if (!enabled) {
      return;
    }
    long assigned = 0;
    Query missing = new Query(Criteria.where("referralCode").exists(false)).limit(BATCH);
    missing.fields().include("_id");
    while (true) {
      var shops = mongoTemplate.find(missing, Shop.class);
      if (shops.isEmpty()) {
        break;
      }
      for (Shop shop : shops) {
        assigned += assign(shop.getShopId()) ? 1 : 0;
      }
    }
    if (assigned > 0) {
      log.info("[referral-code-backfill] assigned {} referral codes", assigned);
    }
  }

  boolean assign(String shopId) {
    Query noCodeYet = new Query(Criteria.where("_id").is(shopId).and("referralCode").exists(false));
    for (int attempt = 1; attempt <= ReferralCodes.MAX_ATTEMPTS; attempt++) {
      try {
        return mongoTemplate.updateFirst(noCodeYet, new Update().set("referralCode", ReferralCodes.generate()), Shop.class)
            .getModifiedCount() > 0;
      } catch (DuplicateKeyException e) {
        log.warn("[referral-code-backfill] code collision for shop {} on attempt {}", shopId, attempt);
      }
    }
    throw new IllegalStateException("Could not assign a unique referral code to shop " + shopId);
  }
}
