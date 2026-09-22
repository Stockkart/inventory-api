package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plugins.cafe.domain.CafeFlushStatus;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotKind;
import com.inventory.plugins.cafe.domain.CafeKotLine;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import com.inventory.plugins.cafe.domain.CafeKotStatus;
import com.inventory.plugins.cafe.domain.CafePendingFlush;
import com.inventory.plugins.cafe.domain.CafeTab;
import com.inventory.plugins.cafe.domain.CafeTabLine;
import com.inventory.plugins.cafe.domain.CafeTabRepository;
import com.inventory.plugins.cafe.domain.CafeTabStatus;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * The four crash windows of a flush: after the claim before any append; after the append before
 * any ticket; between two stations' tickets; and after every ticket before COMPLETE.
 *
 * <p>There is no {@code MongoTransactionManager} in this codebase and a flush touches three
 * documents, so none of these windows can be closed — each is instead made recoverable by
 * retrying with the same idempotency key. Every test here sets up the state a crash would have
 * left behind and then retries, rather than asserting on a happy path that never crashes.
 *
 * <p>The MongoTemplate is faked rather than merely stubbed because the claim's <b>query shape</b>
 * is the correctness: a stub that returns a canned pre-image would pass with the {@code $ne}
 * clause deleted and with {@code returnNew(true)}. The fake interprets the query and the options
 * the way the server would, so breaking either fails a test by name.
 */
class CafeFlushServiceTest {

  private static final String SHOP_ID = "shop-1";
  private static final String USER_ID = "user-1";
  private static final String TAB_ID = "tab-1";
  private static final String BILL_ID = "bill-1";
  private static final String KEY = "idem-1";
  private static final String TABS = "cafe_tabs";
  private static final String PURCHASES = "purchases";

  private FakeMongo fake;
  private MongoTemplate mongoTemplate;
  private CafeKotRepository kotRepository;
  private CafeTabRepository tabRepository;
  private CafeSequenceService sequenceService;
  private CafeTokenService tokenService;
  private CafeFlushService service;

  private final Map<String, CafeKot> kotStore = new LinkedHashMap<>();
  private final AtomicInteger nextKotNo = new AtomicInteger(100);

  @BeforeEach
  void setUp() {
    fake = new FakeMongo();
    mongoTemplate = fake.template();
    kotRepository = mock(CafeKotRepository.class);
    tabRepository = mock(CafeTabRepository.class);
    sequenceService = mock(CafeSequenceService.class);
    tokenService = mock(CafeTokenService.class);

    when(kotRepository.findByShopIdAndFlushId(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              String shopId = invocation.getArgument(0);
              String flushId = invocation.getArgument(1);
              return kotStore.values().stream()
                  .filter(k -> shopId.equals(k.getShopId()) && flushId.equals(k.getFlushId()))
                  .toList();
            });
    when(kotRepository.saveAll(any()))
        .thenAnswer(
            invocation -> {
              List<CafeKot> incoming = new ArrayList<>();
              ((Iterable<CafeKot>) invocation.getArgument(0)).forEach(incoming::add);
              incoming.forEach(k -> kotStore.put(k.getId(), k));
              return incoming;
            });
    when(tabRepository.findByIdAndShopIdAndUserId(anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              String id = invocation.getArgument(0);
              String shopId = invocation.getArgument(1);
              String userId = invocation.getArgument(2);
              CafeTab tab = fake.tab;
              if (tab == null
                  || !id.equals(tab.getId())
                  || !shopId.equals(tab.getShopId())
                  || !userId.equals(tab.getUserId())) {
                return Optional.empty();
              }
              return Optional.of(copy(tab));
            });
    when(sequenceService.allocate(anyString(), any(LocalDate.class), any()))
        .thenAnswer(invocation -> nextKotNo.incrementAndGet());
    when(tokenService.allocateToken(anyString())).thenReturn("7");

    service =
        new CafeFlushService(
            mongoTemplate,
            new CafeTabFlusher(mongoTemplate),
            tabRepository,
            kotRepository,
            sequenceService,
            tokenService);
  }

  // ---------------------------------------------------------------- the seven

  @Test
  void aReplayedKeyClaimsNothingAndReturnsTheOriginalTickets() {
    String flushId = "flush-1";
    fake.tab = tabWithPendingFlush(flushId, CafeFlushStatus.COMPLETE, claimedLines());
    fake.purchase(BILL_ID, flushId);
    CafeKot kitchen = existingTicket(flushId, "KITCHEN", 7);
    CafeKot bar = existingTicket(flushId, "BAR", 8);

    List<CafeKot> replayed = service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    assertEquals(ids(List.of(kitchen, bar)), ids(replayed), "the tickets that key created");
    assertSame(kitchen, byId(replayed, kitchen.getId()), "the stored ticket, not a fresh one");
    verify(kotRepository, never()).saveAll(any());
    verify(sequenceService, never()).allocate(anyString(), any(LocalDate.class), any());
    assertEquals(1, fake.appendCount, "a replay appends nothing further");
  }

  @Test
  void aCrashAfterTheClaimBeforeTheAppendStillAppendsOnRetry() {
    String flushId = "flush-1";
    // The claim landed: lines gone from the tab, recorded on the pendingFlush. Nothing else ran.
    fake.tab = tabWithPendingFlush(flushId, CafeFlushStatus.PENDING, claimedLines());
    fake.purchase(BILL_ID, null);

    List<CafeKot> created = service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    List<Document> billLines = fake.items(BILL_ID);
    assertEquals(2, billLines.size(), "both claimed lines reach the bill on the retry");
    assertEquals(List.of("Tea", "Beer"), billLines.stream().map(d -> d.getString("name")).toList());
    for (Document line : billLines) {
      assertEquals(
          line.get("baseQuantity"),
          line.get("kotSentQuantity"),
          "lines arrive already sent: kotSentQuantity equals the quantity");
      assertNotNull(line.getString("department"), "the frozen station rides onto the bill");
    }
    assertEquals("no onion", billLines.get(0).getString("note"));
    assertTrue(fake.flushIds(BILL_ID).contains(flushId), "the bill records the flush it absorbed");
    assertEquals(2, created.size(), "one ticket per station");
    assertEquals(CafeFlushStatus.COMPLETE, fake.tab.getPendingFlush().getStatus());
  }

  @Test
  void aCrashBetweenTwoStationsTicketsCreatesOnlyTheMissingOne() {
    String flushId = "flush-1";
    fake.tab = tabWithPendingFlush(flushId, CafeFlushStatus.PENDING, claimedLines());
    fake.purchase(BILL_ID, flushId);
    existingTicket(flushId, "KITCHEN", 7);

    List<CafeKot> result = service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    assertEquals(2, result.size(), "the flush still stands for both stations");
    assertEquals(2, kotStore.size(), "and only two tickets exist");
    verify(kotRepository, times(1)).saveAll(any());
    List<CafeKot> saved = savedTickets();
    assertEquals(1, saved.size(), "only the missing station is written");
    assertEquals("BAR", saved.get(0).getDepartment());
    assertEquals(1, fake.appendCount, "the bill already held this flush; it is not appended twice");
  }

  @Test
  void aSurvivingTicketKeepsItsKotNoAcrossARecovery() {
    String flushId = "flush-1";
    fake.tab = tabWithPendingFlush(flushId, CafeFlushStatus.PENDING, claimedLines());
    fake.purchase(BILL_ID, flushId);
    existingTicket(flushId, "KITCHEN", 7);

    List<CafeKot> result = service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    CafeKot kitchen = byId(result, flushId + ":KITCHEN:ISSUE");
    assertEquals(7, kitchen.getKotNo(), "a cook is holding that paper; it is never renumbered");
    assertEquals(7, kotStore.get(kitchen.getId()).getKotNo(), "nor renumbered in the repository");
    // The filter runs BEFORE allocation: exactly one number is drawn, for the one ticket written.
    verify(sequenceService, times(1)).allocate(anyString(), any(LocalDate.class), any());
  }

  @Test
  void aCrashBeforeCompleteMarksCompleteOnRetryAndCreatesNothing() {
    String flushId = "flush-1";
    fake.tab = tabWithPendingFlush(flushId, CafeFlushStatus.PENDING, claimedLines());
    fake.purchase(BILL_ID, flushId);
    existingTicket(flushId, "KITCHEN", 7);
    existingTicket(flushId, "BAR", 8);

    List<CafeKot> result = service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    assertEquals(2, result.size());
    assertEquals(2, kotStore.size(), "nothing new is created");
    verify(sequenceService, never()).allocate(anyString(), any(LocalDate.class), any());
    assertEquals(
        CafeFlushStatus.COMPLETE,
        fake.tab.getPendingFlush().getStatus(),
        "the last step is the one the crash skipped");
    assertEquals(1, fake.appendCount);
  }

  @Test
  void twoConcurrentFlushesOfOneTabProduceOneSetOfTickets() {
    fake.tab = openTabWithLines();
    fake.purchase(BILL_ID, null);

    // Two presses of the same button: the second arrives while the first has already claimed.
    List<CafeKot> first = service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);
    List<CafeKot> second = service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    assertEquals(2, kotStore.size(), "one set of tickets, not two");
    assertEquals(ids(first), ids(second), "the loser returns the winner's tickets");
    assertEquals(1, fake.appendCount, "and the bill is appended to exactly once");
    assertEquals(2, fake.items(BILL_ID).size(), "no duplicated lines on the bill");
  }

  @Test
  void theClaimedLinesLeaveTheTabSoTheNextRoundStartsEmpty() {
    fake.tab = openTabWithLines();
    fake.purchase(BILL_ID, null);

    service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    assertTrue(fake.tab.getLines().isEmpty(), "the tab holds only unsent items");
    CafePendingFlush pending = fake.tab.getPendingFlush();
    assertNotNull(pending, "the claim is the recovery log");
    assertEquals(KEY, pending.getIdempotencyKey());
    assertEquals(BILL_ID, pending.getTargetPurchaseId());
    assertEquals(
        List.of("Tea", "Beer"),
        pending.getLines().stream().map(CafeTabLine::getName).toList(),
        "the claimed lines are recorded on the flush, not lost with the tab's");
    assertFalse(pending.getFlushId().isBlank());
  }

  // ------------------------------------------------------------------ helpers

  private List<CafeKot> savedTickets() {
    return kotStore.values().stream().filter(k -> k.getCreatedBy() != null).toList();
  }

  private static List<String> ids(List<CafeKot> kots) {
    return kots.stream().map(CafeKot::getId).sorted().toList();
  }

  private static CafeKot byId(List<CafeKot> kots, String id) {
    return kots.stream().filter(k -> id.equals(k.getId())).findFirst().orElseThrow();
  }

  private CafeKot existingTicket(String flushId, String department, int kotNo) {
    CafeKot kot = new CafeKot();
    kot.setId(flushId + ":" + department + ":ISSUE");
    kot.setShopId(SHOP_ID);
    kot.setFlushId(flushId);
    kot.setPurchaseId(BILL_ID);
    kot.setDepartment(department);
    kot.setKind(CafeKotKind.ISSUE);
    kot.setStatus(CafeKotStatus.ISSUED);
    kot.setKotNo(kotNo);
    CafeKotLine line = new CafeKotLine();
    line.setName(department.equals("KITCHEN") ? "Tea" : "Beer");
    line.setQuantity(1);
    kot.setLines(new ArrayList<>(List.of(line)));
    kotStore.put(kot.getId(), kot);
    return kot;
  }

  private static List<CafeTabLine> claimedLines() {
    return new ArrayList<>(List.of(line("l1", "Tea", 2, "KITCHEN", "no onion"), line("l2", "Beer", 1, "BAR", null)));
  }

  private static CafeTabLine line(
      String lineRef, String name, int quantity, String department, String note) {
    CafeTabLine line = new CafeTabLine();
    line.setLineRef(lineRef);
    line.setSellableRef("menu:" + name.toLowerCase());
    line.setName(name);
    line.setQuantity(quantity);
    line.setDepartment(department);
    line.setNote(note);
    return line;
  }

  private CafeTab openTabWithLines() {
    CafeTab tab = baseTab();
    tab.setLines(claimedLines());
    return tab;
  }

  private CafeTab tabWithPendingFlush(
      String flushId, CafeFlushStatus status, List<CafeTabLine> lines) {
    CafeTab tab = baseTab();
    tab.setLines(new ArrayList<>());
    CafePendingFlush pending = new CafePendingFlush();
    pending.setFlushId(flushId);
    pending.setIdempotencyKey(KEY);
    pending.setLines(lines);
    pending.setTargetPurchaseId(BILL_ID);
    pending.setStatus(status);
    tab.setPendingFlush(pending);
    return tab;
  }

  private CafeTab baseTab() {
    CafeTab tab = new CafeTab();
    tab.setId(TAB_ID);
    tab.setShopId(SHOP_ID);
    tab.setUserId(USER_ID);
    tab.setTokenNo("3");
    tab.setStatus(CafeTabStatus.OPEN);
    tab.setLines(new ArrayList<>());
    tab.setCreatedAt(Instant.now());
    tab.setUpdatedAt(Instant.now());
    return tab;
  }

  private static CafeTab copy(CafeTab source) {
    CafeTab copy = new CafeTab();
    copy.setId(source.getId());
    copy.setShopId(source.getShopId());
    copy.setUserId(source.getUserId());
    copy.setTokenNo(source.getTokenNo());
    copy.setStatus(source.getStatus());
    copy.setLines(new ArrayList<>(source.getLines()));
    copy.setPendingFlush(source.getPendingFlush());
    copy.setCreatedAt(source.getCreatedAt());
    copy.setUpdatedAt(source.getUpdatedAt());
    return copy;
  }

  /**
   * Enough of a MongoDB server to make the claim's query shape observable: equality on the scoping
   * fields, {@code $ne} on the idempotency key, {@code returnNew} deciding pre- versus post-image,
   * and a {@code $ne}-guarded append that refuses a flush the bill already holds.
   */
  private final class FakeMongo {

    private CafeTab tab;
    private final Map<String, Document> purchases = new LinkedHashMap<>();
    private int appendCount;

    void purchase(String id, String absorbedFlushId) {
      Document doc =
          new Document("_id", id)
              .append("shopId", SHOP_ID)
              .append("userId", USER_ID)
              .append("tokenNo", "7")
              .append("items", new ArrayList<Document>())
              .append("cafeFlushIds", new ArrayList<String>());
      if (absorbedFlushId != null) {
        flushIdsOf(doc).add(absorbedFlushId);
        appendCount++;
      }
      purchases.put(id, doc);
    }

    @SuppressWarnings("unchecked")
    List<Document> items(String purchaseId) {
      return (List<Document>) purchases.get(purchaseId).get("items");
    }

    List<String> flushIds(String purchaseId) {
      return flushIdsOf(purchases.get(purchaseId));
    }

    @SuppressWarnings("unchecked")
    private List<String> flushIdsOf(Document purchase) {
      return (List<String>) purchase.get("cafeFlushIds");
    }

    @SuppressWarnings("unchecked")
    MongoTemplate template() {
      MongoTemplate template = mock(MongoTemplate.class);

      when(template.findAndModify(
              any(Query.class),
              any(UpdateDefinition.class),
              any(FindAndModifyOptions.class),
              eq(CafeTab.class),
              eq(TABS)))
          .thenAnswer(
              invocation ->
                  claim(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2)));

      when(template.findOne(any(Query.class), eq(Document.class), eq(PURCHASES)))
          .thenAnswer(
              invocation -> {
                Document q = ((Query) invocation.getArgument(0)).getQueryObject();
                Document found = purchases.get(q.getString("_id"));
                return found != null && SHOP_ID.equals(found.getString("shopId")) ? found : null;
              });

      when(template.upsert(any(Query.class), any(UpdateDefinition.class), eq(PURCHASES)))
          .thenAnswer(
              invocation -> {
                Document q = ((Query) invocation.getArgument(0)).getQueryObject();
                String id = q.getString("_id");
                if (!purchases.containsKey(id)) {
                  Document insert =
                      new Document("_id", id)
                          .append("items", new ArrayList<Document>())
                          .append("cafeFlushIds", new ArrayList<String>());
                  Document setOnInsert =
                      (Document)
                          ((UpdateDefinition) invocation.getArgument(1))
                              .getUpdateObject()
                              .get("$setOnInsert");
                  if (setOnInsert != null) {
                    setOnInsert.forEach(insert::put);
                  }
                  purchases.put(id, insert);
                  return UpdateResult.acknowledged(0, 0L, new org.bson.BsonString(id));
                }
                return UpdateResult.acknowledged(1, 0L, null);
              });

      when(template.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(PURCHASES)))
          .thenAnswer(invocation -> append(invocation.getArgument(0), invocation.getArgument(1)));

      when(template.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(TABS)))
          .thenAnswer(
              invocation -> markComplete(invocation.getArgument(0), invocation.getArgument(1)));

      return template;
    }

    private CafeTab claim(Query query, UpdateDefinition update, FindAndModifyOptions options) {
      if (tab == null) {
        return null;
      }
      Document q = query.getQueryObject();
      if (!matches(q.get("_id"), tab.getId())
          || !matches(q.get("shopId"), tab.getShopId())
          || !matches(q.get("userId"), tab.getUserId())) {
        return null;
      }
      String recordedKey =
          tab.getPendingFlush() == null ? null : tab.getPendingFlush().getIdempotencyKey();
      if (!matches(q.get("pendingFlush.idempotencyKey"), recordedKey)) {
        return null;
      }

      CafeTab preImage = copy(tab);

      // The update is an aggregation pipeline, and its stages run in order. Applying them in
      // order here is what makes the stage ORDER observable: a recovery log written after the
      // lines are emptied captures an empty array, exactly as the server would.
      List<Document> pipeline = (List<Document>) update.getUpdateObject().get("");
      List<CafeTabLine> lines = new ArrayList<>(tab.getLines());
      CafePendingFlush pending = tab.getPendingFlush();
      for (Document operation : pipeline) {
        Document set = (Document) operation.get("$set");
        if (set == null) {
          continue;
        }
        if (set.containsKey("pendingFlush")) {
          pending = toPendingFlush((Document) set.get("pendingFlush"), lines);
        }
        if (set.containsKey("lines")) {
          lines = new ArrayList<>((List<CafeTabLine>) set.get("lines"));
        }
      }
      tab.setLines(lines);
      tab.setPendingFlush(pending);
      return options.isReturnNew() ? copy(tab) : preImage;
    }

    /** {@code lines} in the stage is a {@code $ifNull} over the document's own array. */
    private CafePendingFlush toPendingFlush(Document stage, List<CafeTabLine> currentLines) {
      CafePendingFlush pending = new CafePendingFlush();
      pending.setFlushId(stage.getString("flushId"));
      pending.setIdempotencyKey(stage.getString("idempotencyKey"));
      pending.setTargetPurchaseId(stage.getString("targetPurchaseId"));
      pending.setStatus(CafeFlushStatus.valueOf(stage.getString("status")));
      pending.setLines(new ArrayList<>(currentLines));
      return pending;
    }

    private UpdateResult append(Query query, UpdateDefinition update) {
      Document q = query.getQueryObject();
      Document purchase = purchases.get(q.getString("_id"));
      if (purchase == null || !matches(q.get("shopId"), purchase.getString("shopId"))) {
        return UpdateResult.acknowledged(0, 0L, null);
      }
      List<String> absorbed = flushIdsOf(purchase);
      Object flushClause = q.get("cafeFlushIds");
      String flushId = null;
      if (flushClause instanceof Document ne && ne.containsKey("$ne")) {
        flushId = (String) ne.get("$ne");
        if (absorbed.contains(flushId)) {
          return UpdateResult.acknowledged(0, 0L, null);
        }
      } else if (flushClause != null) {
        flushId = (String) flushClause;
      }

      Document push = (Document) update.getUpdateObject().get("$push");
      if (push != null) {
        Document each = (Document) push.get("items");
        if (each != null) {
          ((List<Document>) each.get("$each")).forEach(items(purchase)::add);
        }
        Object absorbedPush = push.get("cafeFlushIds");
        if (absorbedPush != null) {
          absorbed.add((String) absorbedPush);
        }
      }
      appendCount++;
      return UpdateResult.acknowledged(1, 1L, null);
    }

    @SuppressWarnings("unchecked")
    private List<Document> items(Document purchase) {
      return (List<Document>) purchase.get("items");
    }

    private UpdateResult markComplete(Query query, UpdateDefinition update) {
      Document q = query.getQueryObject();
      if (tab == null
          || !matches(q.get("_id"), tab.getId())
          || !matches(q.get("shopId"), tab.getShopId())
          || tab.getPendingFlush() == null
          || !matches(q.get("pendingFlush.flushId"), tab.getPendingFlush().getFlushId())) {
        return UpdateResult.acknowledged(0, 0L, null);
      }
      Document set = (Document) update.getUpdateObject().get("$set");
      if (set != null && set.containsKey("pendingFlush.status")) {
        Object status = set.get("pendingFlush.status");
        tab.getPendingFlush()
            .setStatus(
                status instanceof CafeFlushStatus enumValue
                    ? enumValue
                    : CafeFlushStatus.valueOf(String.valueOf(status)));
      }
      return UpdateResult.acknowledged(1, 1L, null);
    }

    /** Equality, or {@code $ne} where the query asks for it; a missing clause matches anything. */
    private boolean matches(Object clause, String actual) {
      if (clause == null) {
        return true;
      }
      if (clause instanceof Document ne && ne.containsKey("$ne")) {
        return !Objects.equals(ne.get("$ne"), actual);
      }
      return Objects.equals(clause, actual);
    }
  }
}
