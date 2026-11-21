package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

class CafeTokenServiceTest {

  private MongoTemplate mongoTemplate;
  private CafeTokenService service;

  @BeforeEach
  void setUp() {
    mongoTemplate = mock(MongoTemplate.class);
    service = new CafeTokenService(mongoTemplate, mock(MetricsWrapper.class));
  }

  private void stubCounter(int nextSequence) {
    when(mongoTemplate.findAndModify(
            any(Query.class),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(Document.class),
            eq("cafe_token_counters")))
        .thenReturn(new Document("nextSequence", nextSequence));
  }

  private Query capturedQuery() {
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate)
        .findAndModify(
            query.capture(),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(Document.class),
            eq("cafe_token_counters"));
    return query.getValue();
  }

  /**
   * The regression this test exists for: counter rows written before scoping existed carry no
   * {@code scope} field. A plain equality match on {@code scope} misses them, the upsert inserts a
   * fresh row, and the day's bill numbering restarts at 1 — two tables holding token "1".
   */
  @Test
  void aCounterRowWrittenBeforeScopingStillContinuesTheBillSequence() {
    stubCounter(5);

    assertEquals("5", service.allocateToken("shop-1"));

    String query = capturedQuery().getQueryObject().toJson();
    assertTrue(
        query.contains("\"$exists\": false"),
        "the bill lookup must also match a row that has no scope field, or it renumbers live"
            + " tokens; query was: "
            + query);
  }

  @Test
  void theBillLookupIsStillKeyedByShopAndBusinessDate() {
    stubCounter(1);

    service.allocateToken("shop-1");

    Document query = capturedQuery().getQueryObject();
    assertEquals("shop-1", query.getString("shopId"));
    assertEquals(LocalDate.now().toString(), query.getString("businessDate"));
  }

  /** A non-bill scope has no legacy rows to tolerate, so it matches its own scope exactly. */
  @Test
  void aScopedCallerMatchesOnlyItsOwnCounter() {
    stubCounter(3);

    assertEquals("3", service.allocateToken("shop-1", "KOT_TAB"));

    String query = capturedQuery().getQueryObject().toJson();
    assertTrue(query.contains("KOT_TAB"), "query was: " + query);
    assertTrue(
        !query.contains("\"$exists\": false"),
        "only the bill scope inherits unscoped rows; query was: " + query);
  }

  @Test
  void anInsertedCounterCarriesItsScope() {
    stubCounter(1);

    service.allocateToken("shop-1", "KOT_TAB");

    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate)
        .findAndModify(
            any(Query.class),
            update.capture(),
            any(FindAndModifyOptions.class),
            eq(Document.class),
            eq("cafe_token_counters"));
    assertTrue(update.getValue().getUpdateObject().toJson().contains("KOT_TAB"));
  }
}
