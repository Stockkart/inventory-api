package com.inventory.plugins.cafe;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plugins.cafe.domain.CafeKot;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Bumps a kitchen ticket's reprint count with one field-level write.
 *
 * <p>There is no {@code MongoTransactionManager} here, and {@code MongoRepository.save} replaces
 * the whole ticket. A reprint only needs {@code reprintCount} and the key that produced it, so
 * those two fields are the whole update.
 *
 * <p>Idempotency is the {@code $ne} on {@code reprintIdempotencyKey} in the query, the same shape
 * as a punch. A retry of the same key matches nothing and returns the ticket already stamped,
 * without incrementing again. A second press with a new key matches and increments.
 */
@Service
@Slf4j
public class CafeKotReprintService {

  private final MongoTemplate mongoTemplate;

  public CafeKotReprintService(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  public CafeKot reprint(String shopId, String kotId, String idempotencyKey) {
    if (!StringUtils.hasText(idempotencyKey)) {
      throw new ValidationException("Idempotency-Key header is required");
    }

    CafeKot claimed = claim(shopId, kotId, idempotencyKey);
    if (claimed != null) {
      return claimed;
    }

    CafeKot existing = find(shopId, kotId);
    if (existing == null) {
      throw new ResourceNotFoundException("CafeKot", "id", kotId);
    }
    if (idempotencyKey.equals(existing.getReprintIdempotencyKey())) {
      log.debug("Cafe reprint {} for kot {} in shop {} is a replay", idempotencyKey, kotId, shopId);
      return existing;
    }

    // A different key landed between the claim and this read. Claim once more; this key still
    // does not match the stored one, so the query still applies.
    CafeKot retried = claim(shopId, kotId, idempotencyKey);
    if (retried != null) {
      return retried;
    }
    existing = find(shopId, kotId);
    if (existing != null && idempotencyKey.equals(existing.getReprintIdempotencyKey())) {
      return existing;
    }
    throw new ResourceNotFoundException("CafeKot", "id", kotId);
  }

  /**
   * @return the ticket after this key's increment, or {@code null} when the query matched nothing
   *     (no such ticket, or this key already stamped it).
   */
  private CafeKot claim(String shopId, String kotId, String idempotencyKey) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(kotId)
                .and("shopId")
                .is(shopId)
                .and("reprintIdempotencyKey")
                .ne(idempotencyKey));

    // $inc refuses a null reprintCount. $ifNull treats a missing or null count as zero, then adds
    // one, in the same write that records the key.
    Document set =
        new Document("reprintIdempotencyKey", idempotencyKey)
            .append(
                "reprintCount",
                new Document(
                    "$add",
                    List.of(new Document("$ifNull", List.of("$reprintCount", 0)), 1)));
    AggregationUpdate update =
        AggregationUpdate.from(List.<AggregationOperation>of(context -> new Document("$set", set)));

    return mongoTemplate.findAndModify(
        query,
        update,
        FindAndModifyOptions.options().returnNew(true).upsert(false),
        CafeKot.class);
  }

  private CafeKot find(String shopId, String kotId) {
    return mongoTemplate.findOne(
        Query.query(Criteria.where("_id").is(kotId).and("shopId").is(shopId)), CafeKot.class);
  }
}
