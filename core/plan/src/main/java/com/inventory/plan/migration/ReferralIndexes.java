package com.inventory.plan.migration;

import com.inventory.plan.domain.model.ReferralAttribution;
import com.inventory.plan.domain.model.ReferralReward;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Referral indexes. Auto index creation is off, so they are ensured on startup. */
@Component
public class ReferralIndexes {

  @Autowired
  private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(23)
  public void ensureIndexes() {
    var attributions = mongoTemplate.indexOps(ReferralAttribution.class);
    attributions.ensureIndex(new Index().on("refereeShopId", Sort.Direction.ASC).unique()
        .named("referee_unique"));
    attributions.ensureIndex(new Index().on("referrerShopId", Sort.Direction.ASC).on("status", Sort.Direction.ASC)
        .named("referrer_status"));
    attributions.ensureIndex(new Index().on("status", Sort.Direction.ASC).on("createdAt", Sort.Direction.ASC)
        .named("status_createdAt"));

    var rewards = mongoTemplate.indexOps(ReferralReward.class);
    rewards.ensureIndex(new Index().on("orderId", Sort.Direction.ASC).unique().named("order_unique"));
    rewards.ensureIndex(new Index().on("refereeShopId", Sort.Direction.ASC).unique().named("referee_unique"));
    rewards.ensureIndex(new Index().on("referrerShopId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
        .named("referrer_createdAt"));
    rewards.ensureIndex(new Index().on("status", Sort.Direction.ASC).on("holdUntil", Sort.Direction.ASC)
        .named("status_holdUntil"));
    rewards.ensureIndex(new Index().on("status", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
        .named("status_createdAt"));
  }
}
