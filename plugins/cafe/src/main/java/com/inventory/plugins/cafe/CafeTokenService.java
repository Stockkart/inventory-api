package com.inventory.plugins.cafe;

import com.inventory.metrics.MetricsWrapper;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Daily per-shop order token sequence for cafe vertical.
 *
 * <p>The counter is keyed by {@code (shopId, businessDate, scope)}. {@link #SCOPE_BILL} is the
 * bill's own token, allocated the same way it always has been; a caller that needs a distinct
 * numbering line — such as a KOT tab — allocates under its own scope so the two numbers can
 * never be read as the same thing, without writing a second allocator.
 */
@Service
@Slf4j
public class CafeTokenService {

  /** The scope the bill's token has always allocated under. */
  public static final String SCOPE_BILL = "BILL";

  private static final String COUNTERS_COLLECTION = "cafe_token_counters";

  private final MongoTemplate mongoTemplate;
  private final MetricsWrapper metrics;

  public CafeTokenService(MongoTemplate mongoTemplate, MetricsWrapper metrics) {
    this.mongoTemplate = mongoTemplate;
    this.metrics = metrics;
  }

  public String allocateToken(String shopId) {
    return allocateToken(shopId, SCOPE_BILL);
  }

  public String allocateToken(String shopId, String scope) {
    LocalDate businessDate = LocalDate.now();
    Query query =
        Query.query(
            Criteria.where("shopId")
                .is(shopId)
                .and("businessDate")
                .is(businessDate.toString())
                .and("scope")
                .is(scope));
    Update update =
        new Update()
            .inc("nextSequence", 1)
            .setOnInsert("shopId", shopId)
            .setOnInsert("businessDate", businessDate.toString())
            .setOnInsert("scope", scope);
    org.bson.Document counter =
        mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true).upsert(true),
            org.bson.Document.class,
            COUNTERS_COLLECTION);
    int seq = counter != null ? counter.getInteger("nextSequence", 1) : 1;
    String tokenNo = String.valueOf(seq);
    log.debug(
        "Allocated cafe token {} for shop {} scope {} on {}", tokenNo, shopId, scope, businessDate);
    metrics.record(
        CafeMetricsConstants.TOKENS_TOTAL,
        1,
        "module",
        CafeMetricsConstants.MODULE,
        "operation",
        "issue");
    return tokenNo;
  }
}
