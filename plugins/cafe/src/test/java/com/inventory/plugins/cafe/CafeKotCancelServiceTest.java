package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotKind;
import com.inventory.plugins.cafe.domain.CafeKotLine;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import com.inventory.plugins.cafe.domain.CafeKotStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * The Sell screen never issues to the kitchen — it only withdraws. Reducing a bill line below its
 * {@code kotSentQuantity} owes a cancellation for the difference; removing it owes one for the
 * whole remainder; raising owes nothing; a line the kitchen never saw owes nothing.
 *
 * <p>The MongoTemplate is faked, not stubbed, for the same reason {@code CafeFlushServiceTest}
 * fakes it: the claim's query shape — the {@code $ne} idempotency guard — is the correctness. A
 * stub that always "succeeds" would pass with that clause deleted.
 */
class CafeKotCancelServiceTest {

  private static final String SHOP_ID = "shop-1";
  private static final String USER_ID = "user-1";
  private static final String BILL_ID = "bill-1";
  private static final String LINE_REF = "menu:tea";
  private static final String KEY = "idem-1";
  private static final String PURCHASES = "purchases";

  private FakeMongo fake;
  private MongoTemplate mongoTemplate;
  private CafeKotRepository kotRepository;
  private CafeSequenceService sequenceService;
  private CafeKotCancelService service;

  private final Map<String, CafeKot> kotStore = new LinkedHashMap<>();
  private final AtomicInteger nextKotNo = new AtomicInteger(100);

  @BeforeEach
  void setUp() {
    fake = new FakeMongo();
    mongoTemplate = fake.template();
    kotRepository = mock(CafeKotRepository.class);
    sequenceService = mock(CafeSequenceService.class);

    when(kotRepository.findByShopIdAndFlushId(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              String shopId = invocation.getArgument(0);
              String cancelId = invocation.getArgument(1);
              return kotStore.values().stream()
                  .filter(k -> shopId.equals(k.getShopId()) && cancelId.equals(k.getFlushId()))
                  .toList();
            });
    when(kotRepository.save(any()))
        .thenAnswer(
            invocation -> {
              CafeKot kot = invocation.getArgument(0);
              kotStore.put(kot.getId(), kot);
              return kot;
            });
    when(sequenceService.allocate(anyString(), any(LocalDate.class), any()))
        .thenAnswer(invocation -> nextKotNo.incrementAndGet());

    service = new CafeKotCancelService(mongoTemplate, kotRepository, sequenceService);
  }

  // ---------------------------------------------------------------- the seven

  @Test
  void reducingBelowTheSentQuantityCancelsTheDifference() {
    fake.bill(billLine(5, "KITCHEN", "no onion"));

    List<CafeKot> result = service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 5, 2, KEY);

    assertEquals(1, result.size());
    assertEquals(3, result.get(0).getLines().get(0).getQuantity());
  }

  @Test
  void removingAFullySentLineCancelsTheWholeRemainder() {
    fake.bill(billLine(5, "KITCHEN", null));

    List<CafeKot> result = service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 5, 0, KEY);

    assertEquals(1, result.size());
    assertEquals(5, result.get(0).getLines().get(0).getQuantity());
  }

  @Test
  void reducingALineTheKitchenNeverSawCancelsNothing() {
    fake.bill(billLine(0, "KITCHEN", null));

    List<CafeKot> result = service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 3, 1, KEY);

    assertTrue(result.isEmpty(), "an unsent line owes nothing");
    verify(kotRepository, never()).save(any());
    assertTrue(fake.cancelsOf(BILL_ID).isEmpty(), "nothing is even claimed");
  }

  @Test
  void raisingALineCancelsNothing() {
    fake.bill(billLine(2, "KITCHEN", null));

    List<CafeKot> result = service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 2, 5, KEY);

    assertTrue(result.isEmpty(), "raising a quantity owes nothing");
    verify(kotRepository, never()).save(any());
  }

  @Test
  void aReplayedCancelKeyCreatesNoSecondTicket() {
    fake.bill(billLine(5, "KITCHEN", "no onion"));

    List<CafeKot> first = service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 5, 2, KEY);
    List<CafeKot> second = service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 5, 2, KEY);

    assertEquals(1, kotStore.size(), "one ticket, not two");
    assertEquals(first.get(0).getId(), second.get(0).getId());
    assertSame(first.get(0), second.get(0), "the stored ticket, not a fresh one");
    verify(kotRepository, times(1)).save(any());
    assertEquals(
        1,
        fake.cancelsOf(BILL_ID).size(),
        "the second attempt's push is rejected by the $ne guard, not appended again");
  }

  @Test
  void aCancelTicketCarriesAbsoluteQuantitiesAndTheCancelledStamp() {
    fake.bill(billLine(5, "KITCHEN", "no onion"));

    List<CafeKot> result = service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 5, 2, KEY);

    CafeKot kot = result.get(0);
    assertEquals(CafeKotKind.CANCEL, kot.getKind(), "the stamp CafeKotService renders as CANCELLED");
    assertEquals(CafeKotStatus.ISSUED, kot.getStatus());
    assertTrue(kot.getLines().get(0).getQuantity() > 0, "absolute, never negative");
    assertEquals(3, kot.getLines().get(0).getQuantity());
    assertEquals("no onion", kot.getLines().get(0).getNote(), "the note rides along for the cook");
  }

  @Test
  void theCancelRoutesToTheStationTheLineWasFrozenTo() {
    // The line was frozen to BAR at compose time. A menu edit since then would resolve "tea" to a
    // different station today, but the cancel must not care: it reads the bill's own field.
    fake.bill(billLine(5, "BAR", null));

    List<CafeKot> result = service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 5, 1, KEY);

    assertEquals("BAR", result.get(0).getDepartment());
    assertTrue(result.get(0).getId().contains(":BAR:CANCEL"));
  }

  @Test
  void twoLinesSharingASellableRefCancelIndependentlyByLineRef() {
    // "2x Tea, no sugar" and "1x Tea, extra hot": same sellableRef, different lineRef and note.
    // Reducing the SECOND line must withdraw the second line's note and quantity, never the
    // first's — the exact break finding 1 describes.
    Document noSugar = billLine("line-1", "menu:tea", 2, "BAR", "no sugar");
    Document extraHot = billLine("line-2", "menu:tea", 1, "BAR", "extra hot");
    fake.bill(List.of(noSugar, extraHot));

    List<CafeKot> result = service.cancel(SHOP_ID, USER_ID, BILL_ID, "line-2", 1, 0, KEY);

    assertEquals(1, result.size());
    CafeKotLine line = result.get(0).getLines().get(0);
    assertEquals(1, line.getQuantity(), "the second line's quantity, not the first's");
    assertEquals("extra hot", line.getNote(), "the second line's note, not the first's");
    assertEquals("line-2", line.getLineId());
  }

  @Test
  void aLineRefNotOnTheBillIsAnExplicitError() {
    fake.bill(billLine(5, "KITCHEN", null));

    assertThrows(
        ResourceNotFoundException.class,
        () -> service.cancel(SHOP_ID, USER_ID, BILL_ID, "no-such-line", 5, 2, KEY));
  }

  @Test
  void reducingToExactlyTheSentQuantityCancelsNothing() {
    // Sent 3, reduced to exactly 3: delta == 0. Must not produce a spurious zero-quantity ticket.
    fake.bill(billLine(3, "KITCHEN", null));

    List<CafeKot> result = service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 3, 3, KEY);

    assertTrue(result.isEmpty(), "delta == 0 owes nothing");
    verify(kotRepository, never()).save(any());
    assertTrue(fake.cancelsOf(BILL_ID).isEmpty(), "nothing is even claimed");
  }

  @Test
  void concurrentCancelsWithDifferentKeysDoNotBothSucceedFromAStaleRead() {
    // Sent 5. Two callers both read the bill while it still shows kotSentQuantity == 5 — a
    // genuine race, modelled here by forcing the SECOND service-level read (the second caller's
    // own requirePurchase) to see the pre-mutation document even though, by then, the first
    // caller's claim has already landed and moved the live line to kotSentQuantity == 2. Both
    // reduce with DIFFERENT idempotency keys. The first must win; the second — computed off a
    // read taken before the first landed — must fail rather than push a second ticket that
    // together over-cancels beyond what was ever sent.
    fake.bill(billLine(5, "KITCHEN", "no onion"));
    Document preRaceSnapshot = fake.snapshot(BILL_ID);

    List<CafeKot> first = service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 5, 2, "key-a");
    assertEquals(1, first.size());
    assertEquals(3, first.get(0).getLines().get(0).getQuantity());

    // The second caller's own read of the bill (its requirePurchase) is forced to the snapshot
    // taken before the first call ran — what it would have seen had it truly raced the first.
    fake.forceNextFindOneToReturn(preRaceSnapshot);

    assertThrows(
        ValidationException.class,
        () -> service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 5, 1, "key-b"),
        "a second writer working off the same stale read must fail and retry, not double-cancel");

    assertEquals(1, kotStore.size(), "only the winning claim ever produced a ticket");
    assertEquals(1, fake.cancelsOf(BILL_ID).size());
  }

  // ------------------------------------------------------------------ helpers

  // ------------------------------------------- B4: a cancel stranded PENDING

  @Test
  void aStrandedPendingCancelIsFinishedByTheNextCancelOnTheBill() {
    // The cashier reduces 5 to 3. K1 claims: it pushes its PENDING record and decrements
    // kotSentQuantity 5 -> 3, then dies before its ticket. The cart write never ran, so the bill
    // still reads 5 to the cashier.
    fake.bill(billLine(5, "KITCHEN", "no onion"));
    failTheNextTicketWrite();
    assertThrows(
        IllegalStateException.class,
        () -> service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 5, 3, "k1"));
    assertEquals("PENDING", fake.cancelsOf(BILL_ID).get(0).getString("status"));
    assertTrue(kotStore.isEmpty(), "nothing reached the kitchen for K1");

    // The cashier does not retry; they reduce again, to 2. The bill now reads sent = 3.
    List<CafeKot> produced = service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 5, 2, "k2");

    // Both records are finished, and the kitchen has been told about every unit withdrawn.
    assertEquals(
        List.of("COMPLETE", "COMPLETE"),
        fake.cancelsOf(BILL_ID).stream().map(r -> r.getString("status")).toList(),
        "no cancel is left PENDING with nothing in the system that will ever look at it");
    assertEquals(2, kotStore.size(), "the stranded round's ticket reached the kitchen too");
    int toldToTheKitchen =
        kotStore.values().stream()
            .mapToInt(kot -> kot.getLines().get(0).getQuantity())
            .sum();
    assertEquals(
        3,
        toldToTheKitchen,
        "5 sent, 2 left on the bill: the kitchen is told about all three withdrawn");
    assertEquals(1, produced.size(), "this call's own ticket is what it returns");
  }

  @Test
  void aCancelClaimIsRefusedWhileTheBillStillOwesAnEarlierOne() {
    // The clause, not the sweep: a PENDING record appearing between the sweep's read and the
    // claim must still refuse it, exactly as CafeTabFlusher's pendingFlush.status clause does.
    fake.bill(billLine(5, "KITCHEN", null));
    fake.cancelsOf(BILL_ID)
        .add(
            new Document("cancelId", "stranded")
                .append("idempotencyKey", "k0")
                .append("status", "PENDING"));
    fake.forceNextFindOneToReturn(
        new Document("_id", BILL_ID)
            .append("shopId", SHOP_ID)
            .append("items", new ArrayList<>(List.of(billLine(5, "KITCHEN", null))))
            .append("cafeKotCancels", new ArrayList<Document>()));

    assertThrows(
        ValidationException.class,
        () -> service.cancel(SHOP_ID, USER_ID, BILL_ID, LINE_REF, 5, 2, "k2"));
    assertEquals(
        1, fake.cancelsOf(BILL_ID).size(), "nothing was claimed over the record that is owed");
  }

  /** The ticket write failing once: the crash that leaves a claimed cancel PENDING. */
  private void failTheNextTicketWrite() {
    org.mockito.Mockito.doThrow(new IllegalStateException("kitchen printer service is down"))
        .doAnswer(
            invocation -> {
              CafeKot kot = invocation.getArgument(0);
              kotStore.put(kot.getId(), kot);
              return kot;
            })
        .when(kotRepository)
        .save(any());
  }

  private static Document billLine(int kotSentQuantity, String department, String note) {
    return billLine(LINE_REF, "menu:tea", kotSentQuantity, department, note);
  }

  private static Document billLine(
      String lineRef, String sellableRef, int kotSentQuantity, String department, String note) {
    return new Document("sellableRef", sellableRef)
        .append("lineRef", lineRef)
        .append("name", "Tea")
        .append("quantity", kotSentQuantity)
        .append("baseQuantity", kotSentQuantity)
        .append("kotSentQuantity", kotSentQuantity)
        .append("department", department)
        .append("note", note);
  }

  /**
   * Enough of a MongoDB server to make the claim's query shape observable: equality on the
   * scoping fields and {@code $ne} on the idempotency key deciding whether the push lands.
   */
  private final class FakeMongo {

    private final Map<String, Document> purchases = new LinkedHashMap<>();
    private int claimAttempts;

    void bill(Document line) {
      bill(List.of(line));
    }

    void bill(List<Document> lines) {
      Document doc =
          new Document("_id", BILL_ID)
              .append("shopId", SHOP_ID)
              .append("tableLabel", "T3")
              .append("tokenNo", "7")
              .append("items", new ArrayList<>(lines))
              .append("cafeKotCancels", new ArrayList<Document>());
      purchases.put(BILL_ID, doc);
    }

    @SuppressWarnings("unchecked")
    List<Document> cancelsOf(String purchaseId) {
      return (List<Document>) purchases.get(purchaseId).get("cafeKotCancels");
    }

    /** A deep copy, frozen at this instant — used to hand a later reader a pre-race view. */
    Document snapshot(String purchaseId) {
      return deepCopy(purchases.get(purchaseId));
    }

    private Document forcedRead;

    /** The very next {@code findOne} call returns this document instead of the live one. */
    void forceNextFindOneToReturn(Document doc) {
      this.forcedRead = doc;
    }

    @SuppressWarnings("unchecked")
    private static Document deepCopy(Document doc) {
      Document copy = new Document();
      doc.forEach((k, v) -> copy.put(k, deepCopyValue(v)));
      return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object v) {
      if (v instanceof Document d) {
        return deepCopy(d);
      }
      if (v instanceof List<?> list) {
        List<Object> out = new ArrayList<>();
        for (Object o : list) {
          out.add(deepCopyValue(o));
        }
        return out;
      }
      return v;
    }

    @SuppressWarnings("unchecked")
    MongoTemplate template() {
      MongoTemplate template = mock(MongoTemplate.class);

      when(template.findOne(any(Query.class), eq(Document.class), eq(PURCHASES)))
          .thenAnswer(
              invocation -> {
                if (forcedRead != null) {
                  Document forced = forcedRead;
                  forcedRead = null;
                  return forced;
                }
                Document q = ((Query) invocation.getArgument(0)).getQueryObject();
                Document found = purchases.get(q.getString("_id"));
                return found != null && SHOP_ID.equals(found.getString("shopId")) ? found : null;
              });

      when(template.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(PURCHASES)))
          .thenAnswer(
              invocation -> update(invocation.getArgument(0), invocation.getArgument(1)));

      return template;
    }

    private com.mongodb.client.result.UpdateResult update(Query query, UpdateDefinition update) {
      Document q = query.getQueryObject();
      Document purchase = purchases.get(q.getString("_id"));
      if (purchase == null || !matches(q.get("shopId"), purchase.getString("shopId"))) {
        return com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null);
      }

      Document set = (Document) update.getUpdateObject().get("$set");
      Document push = (Document) update.getUpdateObject().get("$push");

      // markComplete: a positional $set on cafeKotCancels.$.status, scoped by cancelId.
      if (set != null && set.containsKey("cafeKotCancels.$.status")) {
        String cancelId = asString(q.get("cafeKotCancels.cancelId"));
        for (Document record : cancelsOf(q.getString("_id"))) {
          if (cancelId.equals(record.getString("cancelId"))) {
            record.put("status", set.get("cafeKotCancels.$.status"));
            return com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null);
          }
        }
        return com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null);
      }

      // The claim: $ne-guarded push of a new cancel record, plus an elemMatch on the target
      // line's observed lineRef + kotSentQuantity — the concurrency guard from finding 2.
      claimAttempts++;
      Object clause = q.get("cafeKotCancels.idempotencyKey");
      String key = clause instanceof Document ne ? (String) ne.get("$ne") : (String) clause;
      boolean alreadyClaimed =
          cancelsOf(q.getString("_id")).stream()
              .anyMatch(r -> key.equals(r.getString("idempotencyKey")));
      if (alreadyClaimed) {
        return com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null);
      }

      // The PENDING clause: $ne against an array field means "no element has this value", so a
      // bill that still owes the kitchen a cancel under ANY key refuses the claim.
      if (q.get("cafeKotCancels.status") instanceof Document statusNe
          && statusNe.containsKey("$ne")) {
        boolean owesOne =
            cancelsOf(q.getString("_id")).stream()
                .anyMatch(r -> Objects.equals(statusNe.get("$ne"), r.getString("status")));
        if (owesOne) {
          return com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null);
        }
      }

      Document itemsClause = (Document) q.get("items");
      Document matchedItem = null;
      if (itemsClause != null && itemsClause.get("$elemMatch") instanceof Document elemMatch) {
        String wantLineRef = elemMatch.getString("lineRef");
        Number wantSentQty = (Number) elemMatch.get("kotSentQuantity");
        for (Document item : itemsOf(q.getString("_id"))) {
          if (wantLineRef.equals(item.getString("lineRef"))
              && wantSentQty.intValue() == item.getInteger("kotSentQuantity")) {
            matchedItem = item;
            break;
          }
        }
        if (matchedItem == null) {
          // Stale read: the line's kotSentQuantity has moved since it was observed. The claim
          // does not match, exactly like a real elemMatch would refuse the whole query.
          return com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null);
        }
      }

      if (push != null) {
        Document record = (Document) push.get("cafeKotCancels");
        if (record != null) {
          cancelsOf(q.getString("_id")).add(record);
        }
      }
      if (set != null && matchedItem != null && set.containsKey("items.$.kotSentQuantity")) {
        matchedItem.put("kotSentQuantity", set.get("items.$.kotSentQuantity"));
      }
      return com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null);
    }

    @SuppressWarnings("unchecked")
    private List<Document> itemsOf(String purchaseId) {
      return (List<Document>) purchases.get(purchaseId).get("items");
    }

    private String asString(Object clause) {
      return clause instanceof Document ne ? (String) ne.get("$ne") : (String) clause;
    }

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
