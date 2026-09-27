package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.inventory.metrics.MetricsWrapper;
import java.time.LocalDate;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class CafeSequenceServiceTest {

  private MongoTemplate mongoTemplate;
  private CafeSequenceService service;

  @BeforeEach
  void setUp() {
    mongoTemplate = mock(MongoTemplate.class);
    service = new CafeSequenceService(mongoTemplate, mock(MetricsWrapper.class));
  }

  private void stubCounter(Document result) {
    when(mongoTemplate.findAndModify(
            any(Query.class),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(Document.class),
            eq("cafe_sequences")))
        .thenReturn(result);
  }

  @Test
  void allocateReturnsIncrementedSequence() {
    stubCounter(new Document("nextSequence", 7));

    assertEquals(7, service.allocate("shop-1", LocalDate.of(2026, 9, 20), CafeSequenceSeries.KOT));
  }

  @Test
  void allocateDefaultsToOneWhenCounterMissing() {
    stubCounter(null);

    assertEquals(
        1, service.allocate("shop-1", LocalDate.of(2026, 9, 20), CafeSequenceSeries.KOT));
  }

  @Test
  void theCounterIsKeyedByShopDateAndSeries() {
    // The series discriminator stays in the key even with one series, so a second counter can be
    // added later without migrating the collection's unique index.
    stubCounter(new Document("nextSequence", 1));
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);

    service.allocate("shop-1", LocalDate.of(2026, 9, 20), CafeSequenceSeries.KOT);

    verify(mongoTemplate)
        .findAndModify(
            query.capture(),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(Document.class),
            eq("cafe_sequences"));

    String keyed = query.getValue().getQueryObject().toJson();
    assertTrue(keyed.contains("shop-1"), keyed);
    assertTrue(keyed.contains("2026-09-20"), keyed);
    assertTrue(keyed.contains("KOT"), keyed);
  }

  @Test
  void allocationIsASingleAtomicUpsertNotAReadThenWrite() {
    stubCounter(new Document("nextSequence", 1));
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    ArgumentCaptor<FindAndModifyOptions> options =
        ArgumentCaptor.forClass(FindAndModifyOptions.class);

    service.allocate("shop-1", LocalDate.of(2026, 9, 20), CafeSequenceSeries.KOT);

    verify(mongoTemplate)
        .findAndModify(
            any(Query.class),
            update.capture(),
            options.capture(),
            eq(Document.class),
            eq("cafe_sequences"));
    verifyNoMoreInteractions(mongoTemplate);

    assertTrue(update.getValue().getUpdateObject().containsKey("$inc"));
    assertTrue(options.getValue().isUpsert());
    assertTrue(options.getValue().isReturnNew());
  }
}
