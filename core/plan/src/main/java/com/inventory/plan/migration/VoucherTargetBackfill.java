package com.inventory.plan.migration;

import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.VoucherTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Sets {@code target = ADD_ON} on vouchers issued before plan vouchers existed (no {@code target}
 * stored), so every voucher states what it applies to. Idempotent: only rows without a target are
 * touched. Off by default ({@code stockkart.voucher-target-backfill.enabled}); dry-run only counts.
 */
@Component
@Slf4j
public class VoucherTargetBackfill {

  @Autowired
  private MongoTemplate mongoTemplate;

  @Value("${stockkart.voucher-target-backfill.enabled:false}")
  boolean enabled;

  @Value("${stockkart.voucher-target-backfill.dry-run:false}")
  boolean dryRun;

  @EventListener(ApplicationReadyEvent.class)
  @Order(51)
  public void run() {
    if (!enabled) {
      return;
    }
    Query missing = new Query(Criteria.where("target").is(null));
    long before = mongoTemplate.count(missing, AddOnVoucher.class);
    log.info("[voucher-target-backfill] {} voucher(s) without a target{}", before, dryRun ? " (dry run)" : "");
    if (dryRun || before == 0) {
      return;
    }
    long updated = mongoTemplate.updateMulti(missing, new Update().set("target", VoucherTarget.ADD_ON),
        AddOnVoucher.class).getModifiedCount();
    long after = mongoTemplate.count(missing, AddOnVoucher.class);
    log.info("[voucher-target-backfill] set target ADD_ON on {} voucher(s); {} left without a target", updated, after);
  }
}
