package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plugins.cafe.domain.CafeFlushStatus;
import com.inventory.plugins.cafe.domain.CafeTab;
import com.inventory.plugins.cafe.domain.CafeTabLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * The claim is one write, and its query is the idempotency. These tests pin the exact document
 * that goes to the server, because every clause in it is load-bearing and none of them is
 * observable from a stubbed return value.
 */
class CafeTabFlusherTest {

  private static final String SHOP_ID = "shop-1";
  private static final String USER_ID = "user-1";
  private static final String TAB_ID = "tab-1";

  private MongoTemplate mongoTemplate;
  private CafeTabFlusher flusher;

  @BeforeEach
  void setUp() {
    mongoTemplate = mock(MongoTemplate.class);
    flusher = new CafeTabFlusher(mongoTemplate);
  }

  @Test
  void theClaimQueryExcludesAKeyTheTabAlreadyRecords() {
    flusher.claim(SHOP_ID, USER_ID, TAB_ID, "flush-1", "idem-1", "bill-1");

    Document query = capturedQuery();
    assertEquals(TAB_ID, query.get("_id"));
    assertEquals(SHOP_ID, query.get("shopId"));
    assertEquals(USER_ID, query.get("userId"), "a tab is never reachable across cashiers");

    Object keyClause = query.get("pendingFlush.idempotencyKey");
    Document ne =
        assertInstanceOf(
            Document.class, keyClause, "the idempotency is a $ne in the query, not an index");
    assertEquals("idem-1", ne.get("$ne"));
  }

  @Test
  void theClaimEmptiesTheLinesAndWritesTheRecoveryLog() {
    flusher.claim(SHOP_ID, USER_ID, TAB_ID, "flush-1", "idem-1", "bill-1");

    List<Document> pipeline = capturedPipeline();
    assertEquals(2, pipeline.size(), "one write, two stages, in this order");

    // Stage 1 records the lines as they still are.
    Document recordStage = (Document) pipeline.get(0).get("$set");
    Document pending = (Document) recordStage.get("pendingFlush");
    assertEquals("flush-1", pending.get("flushId"));
    assertEquals("idem-1", pending.get("idempotencyKey"));
    assertEquals("bill-1", pending.get("targetPurchaseId"));
    assertEquals(CafeFlushStatus.PENDING.name(), pending.get("status"));
    assertEquals(
        new Document("$ifNull", List.of("$lines", List.of())),
        pending.get("lines"),
        "the recovery log copies the lines off the document being updated");

    // Stage 2, and only then, empties the tab.
    Document emptyStage = (Document) pipeline.get(1).get("$set");
    assertTrue(((List<?>) emptyStage.get("lines")).isEmpty(), "the tab keeps only unsent items");
    assertFalse(
        recordStage.containsKey("lines"),
        "emptying in stage 1 would capture the empty array as the recovery log");
  }

  @Test
  void theClaimTakesThePreImageSoItCarriesTheClaimedLines() {
    CafeTab preImage = new CafeTab();
    preImage.setId(TAB_ID);
    CafeTabLine line = new CafeTabLine();
    line.setName("Tea");
    preImage.setLines(new ArrayList<>(List.of(line)));
    when(mongoTemplate.findAndModify(
            any(Query.class),
            any(UpdateDefinition.class),
            any(FindAndModifyOptions.class),
            eq(CafeTab.class),
            eq("cafe_tabs")))
        .thenReturn(preImage);

    Optional<CafeTab> claimed = flusher.claim(SHOP_ID, USER_ID, TAB_ID, "flush-1", "idem-1", "bill-1");

    assertTrue(claimed.isPresent());
    assertEquals(List.of("Tea"), claimed.get().getLines().stream().map(CafeTabLine::getName).toList());

    FindAndModifyOptions options = capturedOptions();
    assertFalse(options.isReturnNew(), "the post-image has no lines left to claim");
    assertFalse(options.isUpsert(), "a claim never invents a tab");
  }

  @Test
  void nothingMatchedIsEmptyRatherThanAClaim() {
    when(mongoTemplate.findAndModify(
            any(Query.class),
            any(UpdateDefinition.class),
            any(FindAndModifyOptions.class),
            eq(CafeTab.class),
            eq("cafe_tabs")))
        .thenReturn(null);

    assertTrue(flusher.claim(SHOP_ID, USER_ID, TAB_ID, "flush-1", "idem-1", "bill-1").isEmpty());
  }

  private Document capturedQuery() {
    ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate)
        .findAndModify(
            captor.capture(),
            any(UpdateDefinition.class),
            any(FindAndModifyOptions.class),
            eq(CafeTab.class),
            eq("cafe_tabs"));
    return captor.getValue().getQueryObject();
  }

  @SuppressWarnings("unchecked")
  private List<Document> capturedPipeline() {
    // AggregationUpdate.getUpdateObject() wraps the pipeline under an empty key.
    return (List<Document>) capturedUpdate().getUpdateObject().get("");
  }

  private UpdateDefinition capturedUpdate() {
    ArgumentCaptor<UpdateDefinition> captor = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongoTemplate)
        .findAndModify(
            any(Query.class),
            captor.capture(),
            any(FindAndModifyOptions.class),
            eq(CafeTab.class),
            eq("cafe_tabs"));
    return captor.getValue();
  }

  private FindAndModifyOptions capturedOptions() {
    ArgumentCaptor<FindAndModifyOptions> captor = ArgumentCaptor.forClass(FindAndModifyOptions.class);
    verify(mongoTemplate)
        .findAndModify(
            any(Query.class),
            any(UpdateDefinition.class),
            captor.capture(),
            eq(CafeTab.class),
            eq("cafe_tabs"));
    return captor.getValue();
  }
}
