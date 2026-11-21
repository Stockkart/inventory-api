package com.inventory.plan.migration;

import com.inventory.plan.domain.model.Usage;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Idempotent: ensures the unique {@code shop_month_idx} {@code {shopId, month}} on {@code usage}.
 * Automatic index creation is off, so the {@code @CompoundIndex} on {@link Usage} did nothing, and
 * two requests at the start of a month could each save a record for the same month (after which
 * {@code findByShopIdAndMonth} fails for that shop all month).
 *
 * <p>Before creating the index it merges such duplicates: for each extra record of a (shop, month)
 * it removes the record and adds its counts to the oldest one. A record is removed before its counts
 * are added, so an interrupted run can lose that record's counts but never counts them twice.
 */
@Component
@Slf4j
public class UsageIndexMigration {

  static final String INDEX = "shop_month_idx";
  static final List<String> COUNTERS = List.of("billCountUsed", "smsUsed", "whatsappUsed", "ocrUsed");

  @Autowired
  private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(50)
  public void run() {
    try {
      int merged = mergeDuplicates();
      mongoTemplate.indexOps(Usage.class).ensureIndex(
          new Index().on("shopId", Sort.Direction.ASC).on("month", Sort.Direction.ASC).unique().named(INDEX));
      log.info("[usage-index] merged {} duplicate month record(s); ensured {}", merged, INDEX);
    } catch (RuntimeException e) {
      log.error("[usage-index] failed to ensure {}: {}", INDEX, e.getMessage(), e);
    }
  }

  /** Merges every extra record of a (shop, month) into the oldest one; returns how many were merged. */
  int mergeDuplicates() {
    Aggregation groups = Aggregation.newAggregation(
        Aggregation.sort(Sort.by("createdAt", "_id")),
        Aggregation.group("shopId", "month").push("_id").as("ids").count().as("n"),
        Aggregation.match(Criteria.where("n").gt(1)));
    int merged = 0;
    for (Document group : mongoTemplate.aggregate(groups, "usage", Document.class).getMappedResults()) {
      List<?> ids = group.getList("ids", Object.class);
      Object keep = ids.get(0);
      for (Object extraId : ids.subList(1, ids.size())) {
        Document extra = mongoTemplate.findAndRemove(
            Query.query(Criteria.where("_id").is(extraId)), Document.class, "usage");
        if (extra == null) {
          continue;
        }
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(keep)), countsOf(extra), "usage");
        merged++;
      }
    }
    return merged;
  }

  static Update countsOf(Document extra) {
    Update update = new Update();
    for (String counter : COUNTERS) {
      Object value = extra.get(counter);
      if (value instanceof Number n && n.longValue() != 0) {
        update.inc(counter, n.intValue());
      }
    }
    Object amount = extra.get("billingAmountUsed");
    BigDecimal billed = amount instanceof Decimal128 d ? d.bigDecimalValue()
        : amount instanceof Number n ? new BigDecimal(n.toString()) : BigDecimal.ZERO;
    if (billed.signum() != 0) {
      update.inc("billingAmountUsed", new Decimal128(billed));
    }
    return update.set("updatedAt", new java.util.Date());
  }
}
