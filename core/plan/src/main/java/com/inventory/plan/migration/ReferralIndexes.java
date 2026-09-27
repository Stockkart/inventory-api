package com.inventory.plan.migration;

import com.inventory.plan.domain.model.ReferralAttribution;
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
  }
}
