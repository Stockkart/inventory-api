package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * The punch service turns a reconciled cart into kitchen tickets, and makes an interrupted punch
 * recoverable.
 *
 * <p>The load-bearing property under test is that a replay NEVER reconciles again. The cart has
 * already advanced; a second reconcile would compute every delta as zero and silently drop the
 * tickets the kitchen is owed.
 *
 * <p>Note on types: the punch record's Java model ({@code CafeKotPunch}, {@code CafeKotPunchDelta},
 * {@code CafeKotPunchStatus}) lives in {@code core/product}, which {@code plugins/cafe} does not
 * depend on. Exactly as {@link CafeCartPuncher} does, these tests speak the document shape
 * directly, with the two status values as string literals.
 */
class CafeKotPunchServiceTest {

  private MongoTemplate mongoTemplate;
  private CafeCartPuncher puncher;
  private CafeKotRepository kotRepository;
  private CafeSequenceService sequenceService;
  private CafeKotPunchService service;

  @BeforeEach
  void setUp() {
    mongoTemplate = mock(MongoTemplate.class);
    puncher = mock(CafeCartPuncher.class);
    kotRepository = mock(CafeKotRepository.class);
    sequenceService = mock(CafeSequenceService.class);
    service = new CafeKotPunchService(mongoTemplate, puncher, kotRepository, sequenceService);

    when(sequenceService.allocate(anyString(), any(LocalDate.class), any())).thenReturn(7);
    when(kotRepository.saveAll(any())).thenAnswer(i -> new ArrayList<>(i.getArgument(0)));
  }

  // ---------------------------------------------------------------- fixtures

  private static Document delta(String ref, String name, String department, int quantity) {
    return new Document("sellableRef", ref)
        .append("name", name)
        .append("department", department)
        .append("quantity", quantity)
        .append("note", null);
  }

  private static Document punchDoc(String status, List<Document> deltas, List<String> kotIds) {
    return new Document("punchId", "punch-1")
        .append("idempotencyKey", "key-1")
        .append("status", status)
        .append("deltas", deltas)
        .append("kotIds", kotIds);
  }

  private static Document purchase(List<Document> punches) {
    return new Document("_id", "p1").append("shopId", "shop-1").append("cafeKotPunches", punches);
  }

  /** Every read of the purchase in this test sees exactly this punch already on the cart. */
  private void purchaseHasPunch(Document punch) {
    when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("purchases")))
        .thenReturn(purchase(List.of(punch)));
  }

  /** No punch yet on the first read; after claimAndReconcile the pipeline has appended one. */
  private void purchaseGainsPunchOnClaim(Document appended) {
    when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("purchases")))
        .thenReturn(purchase(List.of()), purchase(List.of(appended)));
    when(puncher.claimAndReconcile(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(purchase(List.of())));
  }

  private List<CafeKot> savedKots() {
    ArgumentCaptor<List<CafeKot>> saved = ArgumentCaptor.forClass(List.class);
    verify(kotRepository).saveAll(saved.capture());
    return saved.getValue();
  }

  private List<String> newlyCreatedKotIds() {
    return savedKots().stream().map(CafeKot::getId).toList();
  }

  private Update capturedPurchaseUpdate() {
    ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongoTemplate).updateFirst(any(Query.class), update.capture(), eq("purchases"));
    return (Update) update.getValue();
  }

  /** The query markComplete matched the punch subdocument by, so a caller can assert its shop scoping. */
  private Query capturedPurchaseUpdateQuery() {
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).updateFirst(query.capture(), any(UpdateDefinition.class), eq("purchases"));
    return query.getValue();
  }

  // ------------------------------------------------------------------- tests

  @Test
  void aFreshPunchReconcilesThenCreatesOneTicketPerStation() {
    purchaseGainsPunchOnClaim(
        punchDoc(
            "PENDING_KOT_CREATION",
            List.of(delta("menu:m1", "Biryani", "KITCHEN", 2), delta("menu:m2", "Coke", "BAR", 1)),
            List.of()));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    verify(puncher).claimAndReconcile(eq("shop-1"), eq("p1"), anyString(), eq("key-1"), eq("user-1"));
    assertEquals(
        List.of("punch-1:KITCHEN:ISSUE", "punch-1:BAR:ISSUE"), newlyCreatedKotIds());
    assertEquals(2, kots.size());

    CafeKot kitchen = kots.get(0);
    assertEquals("KITCHEN", kitchen.getDepartment());
    assertEquals(CafeKotKind.ISSUE, kitchen.getKind());
    assertEquals(CafeKotStatus.ISSUED, kitchen.getStatus());
    assertEquals("punch-1", kitchen.getPunchId());
    assertEquals("p1", kitchen.getPurchaseId());
    assertEquals("shop-1", kitchen.getShopId());
    assertEquals(LocalDate.now().toString(), kitchen.getBusinessDate());
    assertEquals(7, kitchen.getKotNo());
    assertEquals(1, kitchen.getLines().size());

    CafeKotLine line = kitchen.getLines().get(0);
    assertEquals("Biryani", line.getName());
    assertEquals(2, line.getQuantity());
    assertEquals("menu:m1", line.getLineId());

    // And the punch is finished, carrying the ids of the tickets that now exist.
    Update update = capturedPurchaseUpdate();
    Document set = update.getUpdateObject().get("$set", Document.class);
    assertEquals("COMPLETE", set.get("cafeKotPunches.$.status"));
    assertEquals(
        List.of("punch-1:KITCHEN:ISSUE", "punch-1:BAR:ISSUE"),
        set.get("cafeKotPunches.$.kotIds"));

    // markComplete's own match is shop-scoped too, not just the initial punch lookup: the query
    // captured by capturedPurchaseUpdate() above is otherwise verified only with any(Query.class).
    assertEquals("shop-1", capturedPurchaseUpdateQuery().getQueryObject().get("shopId"));
  }

  @Test
  void aNegativeDeltaBecomesACancelTicketCarryingTheAbsoluteQuantity() {
    purchaseGainsPunchOnClaim(
        punchDoc(
            "PENDING_KOT_CREATION",
            List.of(delta("menu:m1", "Biryani", "KITCHEN", 2), delta("menu:m3", "Fries", "KITCHEN", -3)),
            List.of()));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(
        List.of("punch-1:KITCHEN:ISSUE", "punch-1:KITCHEN:CANCEL"), newlyCreatedKotIds());
    CafeKot cancel = kots.get(1);
    assertEquals(CafeKotKind.CANCEL, cancel.getKind());
    assertEquals("KITCHEN", cancel.getDepartment());
    assertEquals(3, cancel.getLines().get(0).getQuantity(), "the kitchen is told how many to stop");
  }

  @Test
  void aCartWithNoDeltasCreatesNothingAndBurnsNoTicketNumber() {
    purchaseGainsPunchOnClaim(punchDoc("PENDING_KOT_CREATION", List.of(), List.of()));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertTrue(kots.isEmpty());
    verify(kotRepository, never()).saveAll(any());
    verify(sequenceService, never()).allocate(anyString(), any(LocalDate.class), any());
    // The punch is still finished: nothing is owed, so nothing must be redriven later.
    assertEquals("COMPLETE", capturedPurchaseUpdate().getUpdateObject()
        .get("$set", Document.class).get("cafeKotPunches.$.status"));
  }

  @Test
  void aCompleteReplayReturnsTheRecordedTicketsAndCreatesNothing() {
    CafeKot existing = new CafeKot();
    existing.setId("punch-1:KITCHEN:ISSUE");
    purchaseHasPunch(
        punchDoc(
            "COMPLETE",
            List.of(delta("menu:m1", "Biryani", "KITCHEN", 2)),
            List.of("punch-1:KITCHEN:ISSUE")));
    when(kotRepository.findByShopIdAndPunchId("shop-1", "punch-1")).thenReturn(List.of(existing));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(List.of("punch-1:KITCHEN:ISSUE"), kots.stream().map(CafeKot::getId).toList());
    verifyNoInteractions(puncher);
    verify(kotRepository, never()).saveAll(any());
    verify(mongoTemplate, never()).updateFirst(any(Query.class), any(UpdateDefinition.class), anyString());
  }

  @Test
  void aPendingReplayFinishesFromTheStoredDeltasAndNeverReconcilesAgain() {
    Document kitchen = delta("menu:m1", "Biryani", "KITCHEN", 2);
    Document bar = delta("menu:m2", "Coke", "BAR", 1);
    // KITCHEN already written by the attempt that died.
    purchaseHasPunch(
        punchDoc("PENDING_KOT_CREATION", List.of(kitchen, bar), List.of("punch-1:KITCHEN:ISSUE")));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    // The cart advanced during the attempt that died. Reconciling again would compute
    // every delta as zero and silently drop the food the kitchen is owed.
    verify(puncher, never()).claimAndReconcile(any(), any(), any(), any(), any());
    assertEquals(List.of("punch-1:BAR:ISSUE"), newlyCreatedKotIds());

    // Only the missing ticket gets a number; the KITCHEN ticket keeps the one it was printed with.
    verify(sequenceService, times(1)).allocate(eq("shop-1"), any(LocalDate.class), eq(CafeSequenceSeries.KOT));

    // The punch now records every ticket of the punch, not just the one written this time.
    Document set = capturedPurchaseUpdate().getUpdateObject().get("$set", Document.class);
    assertEquals(
        List.of("punch-1:KITCHEN:ISSUE", "punch-1:BAR:ISSUE"), set.get("cafeKotPunches.$.kotIds"));
    assertEquals("COMPLETE", set.get("cafeKotPunches.$.status"));
    assertEquals(1, kots.size(), "only the ticket written here is returned when the rest are absent");
  }

  @Test
  void aCrashAfterSaveButBeforeMarkCompleteKeepsTheSurvivingTicketsNumber() {
    // The exact window this task exists for: an earlier attempt's saveAll (:162) landed the
    // KITCHEN ticket in the repository, but the process died before markComplete (:174) ever
    // recorded it in kotIds. kotIds is therefore still empty on this replay, even though the
    // ticket — with a kotNo already printed and in the kitchen's hands — exists.
    Document kitchen = delta("menu:m1", "Biryani", "KITCHEN", 2);
    Document bar = delta("menu:m2", "Coke", "BAR", 1);
    purchaseHasPunch(punchDoc("PENDING_KOT_CREATION", List.of(kitchen, bar), List.of()));
    CafeKot alreadySaved = new CafeKot();
    alreadySaved.setId("punch-1:KITCHEN:ISSUE");
    alreadySaved.setKotNo(42);
    when(kotRepository.findByShopIdAndPunchId("shop-1", "punch-1")).thenReturn(List.of(alreadySaved));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    // Only the genuinely missing BAR ticket is (re)saved, and only it burns a sequence number.
    // If the fix regresses to trusting kotIds alone, this would instead re-save KITCHEN too and
    // allocate a second, fresh kotNo for it.
    assertEquals(List.of("punch-1:BAR:ISSUE"), newlyCreatedKotIds());
    verify(sequenceService, times(1))
        .allocate(eq("shop-1"), any(LocalDate.class), eq(CafeSequenceSeries.KOT));

    CafeKot kitchenTicket =
        kots.stream()
            .filter(k -> k.getId().equals("punch-1:KITCHEN:ISSUE"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        42, kitchenTicket.getKotNo(), "the surviving ticket keeps the number already on paper");
  }

  @Test
  void anEmptyClaimWithThePunchAlreadyOnTheCartIsAReplayNotAMissingCart() {
    // claimAndReconcile returns empty for BOTH "no such cart" and "already punched". Treating
    // empty as not-found would fail a perfectly recoverable retry.
    when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("purchases")))
        .thenReturn(
            purchase(List.of()),
            purchase(
                List.of(
                    punchDoc(
                        "PENDING_KOT_CREATION",
                        List.of(delta("menu:m2", "Coke", "BAR", 1)),
                        List.of()))));
    when(puncher.claimAndReconcile(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(List.of("punch-1:BAR:ISSUE"), kots.stream().map(CafeKot::getId).toList());
  }

  @Test
  void anAbsentCartIsNotFound() {
    when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("purchases")))
        .thenReturn(null);

    assertThrows(
        ResourceNotFoundException.class, () -> service.punch("shop-1", "user-1", "p1", "key-1"));
    verifyNoInteractions(puncher);
  }

  @Test
  void aClaimThatLandsNoPunchOnAnExistingCartIsAnError() {
    when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("purchases")))
        .thenReturn(purchase(List.of()), purchase(List.of()));
    when(puncher.claimAndReconcile(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

    assertThrows(
        ValidationException.class, () -> service.punch("shop-1", "user-1", "p1", "key-1"));
    verify(kotRepository, never()).saveAll(any());
  }

  @Test
  void anIdempotencyKeyIsRequired() {
    assertThrows(
        ValidationException.class, () -> service.punch("shop-1", "user-1", "p1", "  "));
    verifyNoInteractions(mongoTemplate, puncher, kotRepository);
  }

  @Test
  void aZeroDeltaContributesNothing() {
    purchaseGainsPunchOnClaim(
        punchDoc(
            "PENDING_KOT_CREATION",
            List.of(delta("menu:m1", "Biryani", "KITCHEN", 0), delta("menu:m2", "Coke", "BAR", 1)),
            List.of()));

    service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(List.of("punch-1:BAR:ISSUE"), newlyCreatedKotIds());
  }

  @Test
  void everyPurchaseReadIsScopedByShop() {
    purchaseHasPunch(punchDoc("COMPLETE", List.of(), List.of()));

    service.punch("shop-1", "user-1", "p1", "key-1");

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).findOne(query.capture(), eq(Document.class), eq("purchases"));
    assertEquals("shop-1", query.getValue().getQueryObject().get("shopId"));
    assertEquals("p1", query.getValue().getQueryObject().get("_id"));
  }

  @Test
  void anIntegerQuantityOfAnyBsonWidthIsHonoured() {
    // Mongo hands back whatever numeric type the pipeline produced; a Long must not be dropped.
    Document longDelta =
        new Document("sellableRef", "menu:m1")
            .append("name", "Biryani")
            .append("department", "KITCHEN")
            .append("quantity", 2L)
            .append("note", "extra spicy");
    purchaseGainsPunchOnClaim(punchDoc("PENDING_KOT_CREATION", List.of(longDelta), List.of()));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(2, kots.get(0).getLines().get(0).getQuantity());
    assertEquals("extra spicy", kots.get(0).getLines().get(0).getNote());
  }

  @Test
  void aBlankDepartmentFallsBackToTheDefaultStation() {
    purchaseGainsPunchOnClaim(
        punchDoc("PENDING_KOT_CREATION", List.of(delta("menu:m1", "Biryani", null, 1)), List.of()));

    service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(List.of("punch-1:KITCHEN:ISSUE"), newlyCreatedKotIds());
  }

  @Test
  void ticketNumbersAreAllocatedOncePerCreatedTicket() {
    purchaseGainsPunchOnClaim(
        punchDoc(
            "PENDING_KOT_CREATION",
            List.of(delta("menu:m1", "Biryani", "KITCHEN", 2), delta("menu:m2", "Coke", "BAR", -1)),
            List.of()));

    service.punch("shop-1", "user-1", "p1", "key-1");

    verify(sequenceService, times(2))
        .allocate(eq("shop-1"), eq(LocalDate.now()), eq(CafeSequenceSeries.KOT));
    // One write for both tickets, and every ticket carries its number and the cart's round.
    List<CafeKot> saved = savedKots();
    assertEquals(2, saved.size());
    saved.forEach(
        k -> {
          assertEquals(7, k.getKotNo());
          assertEquals(1, k.getRoundNo(), "the punch is the cart's first round");
        });
  }

  @Test
  void aLaterPunchCarriesItsRoundNumber() {
    Document earlier =
        new Document("punchId", "punch-0").append("idempotencyKey", "key-0").append("status", "COMPLETE");
    when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("purchases")))
        .thenReturn(
            purchase(List.of(earlier)),
            purchase(
                List.of(
                    earlier,
                    punchDoc(
                        "PENDING_KOT_CREATION",
                        List.of(delta("menu:m2", "Coke", "BAR", 1)),
                        List.of()))));
    when(puncher.claimAndReconcile(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(purchase(List.of(earlier))));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(2, kots.get(0).getRoundNo());
  }

  @Test
  void aPendingReplayReturnsEveryTicketOfThePunchWhenTheEarlierOnesAreReadable() {
    CafeKot already = new CafeKot();
    already.setId("punch-1:KITCHEN:ISSUE");
    CafeKot fresh = new CafeKot();
    fresh.setId("punch-1:BAR:ISSUE");
    purchaseHasPunch(
        punchDoc(
            "PENDING_KOT_CREATION",
            List.of(delta("menu:m1", "Biryani", "KITCHEN", 2), delta("menu:m2", "Coke", "BAR", 1)),
            List.of("punch-1:KITCHEN:ISSUE")));
    when(kotRepository.findByShopIdAndPunchId("shop-1", "punch-1"))
        .thenReturn(List.of(already, fresh));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(
        List.of("punch-1:KITCHEN:ISSUE", "punch-1:BAR:ISSUE"),
        kots.stream().map(CafeKot::getId).toList());
  }
}
