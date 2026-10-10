package com.inventory.plugins.cafe;

import com.inventory.metrics.MetricsWrapper;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Daily per-shop counters for cafe running orders and kitchen tickets.
 *
 * <p>Allocation is a single atomic {@code findAndModify} with {@code $inc} and upsert — never a
 * read followed by a write. Uniqueness under concurrency rests on that atomic update plus the
 * unique index on {@code (shopId, businessDate, series)}, not on anything in this class.
 */
@Service
@Slf4j
public class CafeSequenceService {

  private static final String COLLECTION = "cafe_sequences";

  private final MongoTemplate mongoTemplate;
  private final MetricsWrapper metrics;

  public CafeSequenceService(MongoTemplate mongoTemplate, MetricsWrapper metrics) {
    this.mongoTemplate = mongoTemplate;
    this.metrics = metrics;
  }

  public int allocate(String shopId, LocalDate businessDate, CafeSequenceSeries series) {
    Query query =
        Query.query(
            Criteria.where("shopId")
                .is(shopId)
                .and("businessDate")
                .is(businessDate.toString())
                .and("series")
                .is(series.name()));
    Update update =
        new Update()
            .inc("nextSequence", 1)
            .setOnInsert("shopId", shopId)
            .setOnInsert("businessDate", businessDate.toString())
            .setOnInsert("series", series.name());

    Document counter =
        mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true).upsert(true),
            Document.class,
            COLLECTION);

    int sequence = counter != null ? counter.getInteger("nextSequence", 1) : 1;
    log.debug(
        "Allocated cafe {} sequence {} for shop {} on {}",
        series,
        sequence,
        shopId,
        businessDate);
    metrics.record(
        CafeMetricsConstants.SEQUENCES_TOTAL,
        1,
        "module",
        CafeMetricsConstants.MODULE,
        "series",
        series.name());
    return sequence;
  }
}
