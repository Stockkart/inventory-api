package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
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
 * The whole correctness argument of cafe KOT punching lives in one {@code findAndModify}. These
 * tests pin the shape of that write: one call, pre-image, no upsert, idempotency in the query, and
 * — above all — the stage ORDER, because a reversed pipeline records every delta as zero and the
 * kitchen silently receives nothing.
 */
class CafeCartPuncherTest {

  private MongoTemplate mongoTemplate;
  private CafeCartPuncher puncher;

  @BeforeEach
  void setUp() {
    mongoTemplate = mock(MongoTemplate.class);
    puncher = new CafeCartPuncher(mongoTemplate);
  }

  private void stub(Document preImage) {
    when(mongoTemplate.findAndModify(
            any(Query.class),
            any(UpdateDefinition.class),
            any(FindAndModifyOptions.class),
            eq(Document.class),
            eq("purchases")))
        .thenReturn(preImage);
  }

  private Document punch() {
    return puncher.claimAndReconcile("shop-1", "pur-1", "punch-1", "idem-1", "user-1").orElse(null);
  }

  /** Punches once, then hands back the pipeline the single write actually carried. */
  private List<Document> capturePipeline() {
    punch();
    ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongoTemplate)
        .findAndModify(
            any(Query.class),
            update.capture(),
            any(FindAndModifyOptions.class),
            eq(Document.class),
            eq("purchases"));
    UpdateDefinition captured = update.getValue();
    assertInstanceOf(
        AggregationUpdate.class, captured, "the update must be an aggregation pipeline");
    return ((AggregationUpdate) captured).toPipeline(Aggregation.DEFAULT_CONTEXT);
  }

  @Test
  void reconcileIsExactlyOneWrite() {
    stub(new Document("_id", "pur-1"));

    punch();

    verify(mongoTemplate)
        .findAndModify(
            any(Query.class),
            any(UpdateDefinition.class),
            any(FindAndModifyOptions.class),
            eq(Document.class),
            eq("purchases"));
    verifyNoMoreInteractions(mongoTemplate);
  }

  @Test
  void returnsThePreImageNotThePostImage() {
    Document preImage = new Document("_id", "pur-1");
    stub(preImage);

    Optional<Document> result =
        puncher.claimAndReconcile("shop-1", "pur-1", "punch-1", "idem-1", "user-1");

    assertTrue(result.isPresent());
    assertSame(preImage, result.get());

    ArgumentCaptor<FindAndModifyOptions> options =
        ArgumentCaptor.forClass(FindAndModifyOptions.class);
    verify(mongoTemplate)
        .findAndModify(
            any(Query.class),
            any(UpdateDefinition.class),
            options.capture(),
            eq(Document.class),
            eq("purchases"));

    // A post-image shows every line already reconciled, so every delta computes to zero.
    assertFalse(options.getValue().isReturnNew(), "returnNew must be false: the caller needs the "
        + "PRE-image, or every delta reads as zero");
    assertFalse(options.getValue().isUpsert(), "upsert must be false: never conjure a cart");
  }

  @Test
  void noMatchBecomesEmpty() {
    stub(null);

    assertEquals(
        Optional.empty(),
        puncher.claimAndReconcile("shop-1", "pur-1", "punch-1", "idem-1", "user-1"));
  }

  @Test
  void queryScopesByShopAndPurchaseAndCarriesTheIdempotencyGuard() {
    stub(new Document("_id", "pur-1"));
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);

    punch();

    verify(mongoTemplate)
        .findAndModify(
            query.capture(),
            any(UpdateDefinition.class),
            any(FindAndModifyOptions.class),
            eq(Document.class),
            eq("purchases"));

    Document q = query.getValue().getQueryObject();
    assertEquals("pur-1", q.get("_id"));
    assertEquals("shop-1", q.get("shopId"));
    // The query clause IS the idempotency: a unique multikey index cannot stop a second append
    // of the same key into one document's array.
    assertEquals(
        new Document("$ne", "idem-1"),
        q.get("cafeKotPunches.idempotencyKey"),
        "the write must not match a cart that already carries this key");
  }

  @Test
  void updateIsAPipelineOfExactlyTwoSetStages() {
    stub(new Document("_id", "pur-1"));

    List<Document> pipeline = capturePipeline();

    assertEquals(2, pipeline.size(), pipeline.toString());
    assertTrue(pipeline.get(0).containsKey("$set"), pipeline.get(0).toJson());
    assertTrue(pipeline.get(1).containsKey("$set"), pipeline.get(1).toJson());
  }

  /**
   * The load-bearing assertion. Asserting only that both stages exist would pass on a reversed
   * pipeline, where the punch is appended after the lines have already been advanced and every
   * recorded delta is therefore zero.
   */
  @Test
  void punchAppendStageStrictlyPrecedesTheLineReconcileStage() {
    stub(new Document("_id", "pur-1"));

    List<Document> pipeline = capturePipeline();

    int punchStage = indexOfStageSetting(pipeline, "cafeKotPunches");
    int itemsStage = indexOfStageSetting(pipeline, "items");

    assertEquals(0, punchStage, "the punch must be appended FIRST, while the lines still differ");
    assertEquals(1, itemsStage, "the lines must be reconciled SECOND: " + pipeline);
    assertTrue(punchStage < itemsStage, "reversed, every recorded delta would be zero");
  }

  /**
   * Looks a stage up by the field it sets, never by position, so that
   * {@link #punchAppendStageStrictlyPrecedesTheLineReconcileStage} is the single guardian of
   * ordering and the content tests below fail only on content.
   */
  private Document stageSetting(List<Document> pipeline, String field) {
    return pipeline
        .get(indexOfStageSetting(pipeline, field))
        .get("$set", Document.class)
        .get(field, Document.class);
  }

  private int indexOfStageSetting(List<Document> pipeline, String field) {
    for (int i = 0; i < pipeline.size(); i++) {
      Document set = pipeline.get(i).get("$set", Document.class);
      if (set != null && set.containsKey(field)) {
        return i;
      }
    }
    throw new AssertionError("no $set stage touching '" + field + "' in " + pipeline);
  }

  @Test
  void punchRecordCarriesIdentityStatusAndAuthor() {
    stub(new Document("_id", "pur-1"));

    Document punch = appendedPunch(capturePipeline());

    assertEquals("punch-1", punch.get("punchId"));
    assertEquals("idem-1", punch.get("idempotencyKey"));
    assertEquals("user-1", punch.get("createdBy"));
    assertEquals("PENDING_KOT_CREATION", punch.get("status"));
    assertEquals(List.of(), punch.get("kotIds"));
  }

  @Test
  void deltaIsBaseMinusPunchedNullSafeAndFilteredToNonZero() {
    stub(new Document("_id", "pur-1"));

    String deltas = appendedPunch(capturePipeline()).get("deltas", Document.class).toJson();

    assertTrue(deltas.contains("$subtract"), deltas);
    assertTrue(deltas.contains("$$line.baseQuantity"), deltas);
    assertTrue(deltas.contains("$$line.kotPunchedQuantity"), deltas);
    // null-safe on the punched side — a never-punched line must read as 0, not null
    assertTrue(deltas.contains("$ifNull"), deltas);
    // only the lines that actually moved reach the kitchen
    assertTrue(deltas.contains("$filter"), deltas);
    assertTrue(deltas.contains("$ne"), deltas);
  }

  @Test
  void deltaCarriesEverythingTicketCreationNeedsSoThereIsNoSecondLookup() {
    stub(new Document("_id", "pur-1"));

    Document deltas = appendedPunch(capturePipeline()).get("deltas", Document.class);
    Document mapped =
        deltas
            .get("$filter", Document.class)
            .get("input", Document.class)
            .get("$map", Document.class)
            .get("in", Document.class);

    assertEquals("$$line.sellableRef", mapped.get("sellableRef"));
    assertEquals("$$line.name", mapped.get("name"));
    assertEquals("$$line.department", mapped.get("department"));
    assertEquals("$$line.note", mapped.get("note"));
    assertTrue(mapped.containsKey("quantity"), mapped.toJson());
  }

  @Test
  void appendPreservesExistingPunches() {
    stub(new Document("_id", "pur-1"));

    String json = stageSetting(capturePipeline(), "cafeKotPunches").toJson();

    assertTrue(json.contains("$concatArrays"), json);
    assertTrue(json.contains("cafeKotPunches"), json);
  }

  @Test
  void reconcileAdvancesEveryLineToItsOwnBaseQuantityAndDropsTheEmptyOnes() {
    stub(new Document("_id", "pur-1"));

    String items = stageSetting(capturePipeline(), "items").toJson();

    assertTrue(items.contains("kotPunchedQuantity"), items);
    assertTrue(items.contains("$$line.baseQuantity"), items);
    assertTrue(items.contains("$mergeObjects"), items);
    // a line left at base 0 / punched 0 is dead weight and must go
    assertTrue(items.contains("$filter"), items);
  }

  @SuppressWarnings("unchecked")
  private Document appendedPunch(List<Document> pipeline) {
    List<Object> parts =
        (List<Object>) stageSetting(pipeline, "cafeKotPunches").get("$concatArrays");
    List<Object> appended = (List<Object>) parts.get(1);
    return (Document) appended.get(0);
  }
}
