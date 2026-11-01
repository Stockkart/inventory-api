package com.inventory.plan.migration;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.ReferralReward;
import com.inventory.plan.domain.model.ShopCreditEntry;
import com.inventory.plan.domain.model.VoucherRedemption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Date-range indexes for the revenue MIS (5e). Auto index creation is off, so they are ensured on startup. */
@Component
public class MisIndexes {

  @Autowired
  private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(26)
  public void ensureIndexes() {
    var orders = mongoTemplate.indexOps(PlanPaymentOrder.class);
    orders.ensureIndex(new Index().on("paidAt", Sort.Direction.ASC).sparse().named("paidAt"));
    orders.ensureIndex(new Index().on("refundedAt", Sort.Direction.ASC).sparse().named("refundedAt"));

    var rewards = mongoTemplate.indexOps(ReferralReward.class);
    rewards.ensureIndex(new Index().on("createdAt", Sort.Direction.ASC).named("createdAt"));
    rewards.ensureIndex(new Index().on("creditedAt", Sort.Direction.ASC).sparse().named("creditedAt"));
    rewards.ensureIndex(new Index().on("clawedBackAt", Sort.Direction.ASC).sparse().named("clawedBackAt"));

    mongoTemplate.indexOps(VoucherRedemption.class).ensureIndex(new Index()
        .on("status", Sort.Direction.ASC).on("redeemedAt", Sort.Direction.ASC).named("status_redeemedAt"));
    mongoTemplate.indexOps(ShopCreditEntry.class).ensureIndex(new Index()
        .on("createdAt", Sort.Direction.ASC).named("createdAt"));
  }
}
