package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plugins.cafe.domain.CafeKot;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * A reprint must bump {@code reprintCount} once per idempotency key, with a field-level write.
 * {@code save} would replace the ticket, and ignoring the key would stamp a second REPRINT on
 * every retry of the same press.
 */
class CafeKotReprintServiceTest {

  private MongoTemplate mongoTemplate;
  private CafeKotReprintService service;

  @BeforeEach
  void setUp() {
    mongoTemplate = mock(MongoTemplate.class);
    service = new CafeKotReprintService(mongoTemplate);
  }

  @Test
  void reprintIncrementsWithAFieldLevelWriteGuardedByTheKey() {
    CafeKot bumped = kot();
    bumped.setReprintCount(1);
    bumped.setReprintIdempotencyKey("idem-1");
    when(mongoTemplate.findAndModify(
            any(Query.class),
            any(UpdateDefinition.class),
            any(FindAndModifyOptions.class),
            eq(CafeKot.class)))
        .thenReturn(bumped);

    CafeKot result = service.reprint("shop-1", "k1", "idem-1");

    assertSame(bumped, result);
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
    ArgumentCaptor<FindAndModifyOptions> options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
    verify(mongoTemplate)
        .findAndModify(query.capture(), update.capture(), options.capture(), eq(CafeKot.class));
    verify(mongoTemplate, never()).findOne(any(Query.class), eq(CafeKot.class));

    Document filter = query.getValue().getQueryObject();
    assertEquals("k1", filter.get("_id"));
    assertEquals("shop-1", filter.get("shopId"));
    assertEquals(new Document("$ne", "idem-1"), filter.get("reprintIdempotencyKey"));

    assertInstanceOf(AggregationUpdate.class, update.getValue());
    List<Document> pipeline =
        ((AggregationUpdate) update.getValue()).toPipeline(Aggregation.DEFAULT_CONTEXT);
    assertEquals(1, pipeline.size());
    Document set = pipeline.get(0).get("$set", Document.class);
    assertEquals("idem-1", set.get("reprintIdempotencyKey"));
    assertEquals(
        new Document("$add", List.of(new Document("$ifNull", List.of("$reprintCount", 0)), 1)),
        set.get("reprintCount"));

    assertFalse(options.getValue().isUpsert());
    assertEquals(true, options.getValue().isReturnNew());
  }

  @Test
  void aReplayOfTheSameKeyDoesNotIncrementAgain() {
    CafeKot stored = kot();
    stored.setReprintCount(1);
    stored.setReprintIdempotencyKey("idem-1");
    when(mongoTemplate.findAndModify(
            any(Query.class),
            any(UpdateDefinition.class),
            any(FindAndModifyOptions.class),
            eq(CafeKot.class)))
        .thenReturn(null);
    when(mongoTemplate.findOne(any(Query.class), eq(CafeKot.class))).thenReturn(stored);

    CafeKot result = service.reprint("shop-1", "k1", "idem-1");

    assertSame(stored, result);
    assertEquals(1, result.getReprintCount());
    verify(mongoTemplate, times(1))
        .findAndModify(
            any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(CafeKot.class));
  }

  @Test
  void aMissingTicketIsNotFound() {
    when(mongoTemplate.findAndModify(
            any(Query.class),
            any(UpdateDefinition.class),
            any(FindAndModifyOptions.class),
            eq(CafeKot.class)))
        .thenReturn(null);
    when(mongoTemplate.findOne(any(Query.class), eq(CafeKot.class))).thenReturn(null);

    assertThrows(ResourceNotFoundException.class, () -> service.reprint("shop-1", "missing", "idem-1"));
  }

  @Test
  void aBlankKeyIsRejectedBeforeAnyWrite() {
    assertThrows(ValidationException.class, () -> service.reprint("shop-1", "k1", "  "));
    verify(mongoTemplate, never())
        .findAndModify(
            any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(CafeKot.class));
  }

  private static CafeKot kot() {
    CafeKot kot = new CafeKot();
    kot.setId("k1");
    kot.setShopId("shop-1");
    kot.setReprintCount(0);
    return kot;
  }
}
