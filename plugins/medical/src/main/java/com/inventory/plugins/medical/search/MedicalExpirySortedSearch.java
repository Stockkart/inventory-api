package com.inventory.plugins.medical.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AddFieldsOperation;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.aggregation.LimitOperation;
import org.springframework.data.mongodb.core.aggregation.SkipOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.util.StringUtils;

/** Medical extension collection search sorted by expiry (soonest first) with cursor / skip. */
public final class MedicalExpirySortedSearch {

  private MedicalExpirySortedSearch() {}

  public record ExpirySortedPage(List<String> inventoryIds, String nextCursor) {}

  public static ExpirySortedPage findSortedByExpiry(
      MongoTemplate mongoTemplate,
      Class<?> documentClass,
      Criteria matchCriteria,
      String cursor,
      int skip,
      int limit) {
    int effectiveLimit = limit > 0 ? Math.min(limit, 200) : 50;
    int effectiveSkip = Math.max(skip, 0);

    List<AggregationOperation> ops = new ArrayList<>();
    ops.add(Aggregation.match(matchCriteria));

    if (StringUtils.hasText(cursor)) {
      Criteria cursorCriteria = MedicalInventorySearchCursorCodec.cursorAfter(cursor);
      if (!cursorCriteria.getCriteriaObject().isEmpty()) {
        ops.add(Aggregation.match(cursorCriteria));
      }
      effectiveSkip = 0;
    }

    ops.add(
        AddFieldsOperation.builder()
            .addFieldWithValue(
                "sortExpiry",
                ConditionalOperators.ifNull("expiryDate")
                    .then(MedicalInventorySearchCursorCodec.nullExpirySortSentinel()))
            .build());
    ops.add(
        Aggregation.sort(
            Sort.by(Sort.Order.asc("sortExpiry"), Sort.Order.asc("inventoryId"))));

    if (effectiveSkip > 0) {
      ops.add(new SkipOperation(effectiveSkip));
    }
    ops.add(new LimitOperation(effectiveLimit + 1L));

    Aggregation aggregation = Aggregation.newAggregation(ops);
    AggregationResults<org.bson.Document> results =
        mongoTemplate.aggregate(
            aggregation, mongoTemplate.getCollectionName(documentClass), org.bson.Document.class);

    List<String> ids = new ArrayList<>();
    Instant lastExpiry = null;
    String lastInventoryId = null;
    int count = 0;
    for (org.bson.Document doc : results.getMappedResults()) {
      if (count >= effectiveLimit) {
        break;
      }
      Object inventoryId = doc.get("inventoryId");
      if (inventoryId != null && StringUtils.hasText(String.valueOf(inventoryId))) {
        String id = String.valueOf(inventoryId);
        ids.add(id);
        Object expiryRaw = doc.get("expiryDate");
        lastExpiry = expiryRaw instanceof Instant instant ? instant : null;
        lastInventoryId = id;
        count++;
      }
    }

    String nextCursor = null;
    if (results.getMappedResults().size() > effectiveLimit && lastInventoryId != null) {
      nextCursor = MedicalInventorySearchCursorCodec.encode(lastExpiry, lastInventoryId);
    }
    return new ExpirySortedPage(ids, nextCursor);
  }
}
