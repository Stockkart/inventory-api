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

/** Daily per-shop order token sequence for cafe vertical. */
@Service
@Slf4j
public class CafeTokenService {

  private static final String COUNTERS_COLLECTION = "cafe_token_counters";

  private final MongoTemplate mongoTemplate;
  private final MetricsWrapper metrics;

  public CafeTokenService(MongoTemplate mongoTemplate, MetricsWrapper metrics) {
    this.mongoTemplate = mongoTemplate;
    this.metrics = metrics;
  }

  public String allocateToken(String shopId) {
    LocalDate businessDate = LocalDate.now();
    Query query =
        Query.query(
            Criteria.where("shopId")
                .is(shopId)
                .and("businessDate")
                .is(businessDate.toString()));
    Update update =
        new Update()
            .inc("nextSequence", 1)
            .setOnInsert("shopId", shopId)
            .setOnInsert("businessDate", businessDate.toString());
    org.bson.Document counter =
        mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true).upsert(true),
            org.bson.Document.class,
            COUNTERS_COLLECTION);
    int seq = counter != null ? counter.getInteger("nextSequence", 1) : 1;
    String tokenNo = String.valueOf(seq);
    log.debug("Allocated cafe token {} for shop {} on {}", tokenNo, shopId, businessDate);
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
