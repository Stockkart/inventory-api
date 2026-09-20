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
        1, service.allocate("shop-1", LocalDate.of(2026, 9, 20), CafeSequenceSeries.ORDER));
  }

  @Test
  void orderAndKotSeriesAreKeyedSeparately() {
    stubCounter(new Document("nextSequence", 1));
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);

    service.allocate("shop-1", LocalDate.of(2026, 9, 20), CafeSequenceSeries.ORDER);
    service.allocate("shop-1", LocalDate.of(2026, 9, 20), CafeSequenceSeries.KOT);

    verify(mongoTemplate, org.mockito.Mockito.times(2))
        .findAndModify(
            query.capture(),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(Document.class),
            eq("cafe_sequences"));

    String first = query.getAllValues().get(0).getQueryObject().toJson();
    String second = query.getAllValues().get(1).getQueryObject().toJson();
    assertTrue(first.contains("ORDER"), first);
    assertTrue(second.contains("KOT"), second);
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
