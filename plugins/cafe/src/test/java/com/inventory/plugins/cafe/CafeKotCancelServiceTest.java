package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotKind;
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

  // ------------------------------------------------------------------ helpers

  private static Document billLine(int kotSentQuantity, String department, String note) {
    return new Document("sellableRef", LINE_REF)
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
      Document doc =
          new Document("_id", BILL_ID)
              .append("shopId", SHOP_ID)
              .append("tableLabel", "T3")
              .append("tokenNo", "7")
              .append("items", new ArrayList<>(List.of(line)))
              .append("cafeKotCancels", new ArrayList<Document>());
      purchases.put(BILL_ID, doc);
    }

    @SuppressWarnings("unchecked")
    List<Document> cancelsOf(String purchaseId) {
      return (List<Document>) purchases.get(purchaseId).get("cafeKotCancels");
    }

    @SuppressWarnings("unchecked")
    MongoTemplate template() {
      MongoTemplate template = mock(MongoTemplate.class);

      when(template.findOne(any(Query.class), eq(Document.class), eq(PURCHASES)))
          .thenAnswer(
              invocation -> {
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

      // The claim: $ne-guarded push of a new cancel record.
      claimAttempts++;
      Object clause = q.get("cafeKotCancels.idempotencyKey");
      String key = clause instanceof Document ne ? (String) ne.get("$ne") : (String) clause;
      boolean alreadyClaimed =
          cancelsOf(q.getString("_id")).stream()
              .anyMatch(r -> key.equals(r.getString("idempotencyKey")));
      if (alreadyClaimed) {
        return com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null);
      }
      if (push != null) {
        Document record = (Document) push.get("cafeKotCancels");
        if (record != null) {
          cancelsOf(q.getString("_id")).add(record);
        }
      }
      return com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null);
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
