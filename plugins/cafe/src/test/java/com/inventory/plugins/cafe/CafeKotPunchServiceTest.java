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
import com.inventory.pluginengine.kot.CafeCartPunchPort;
import com.inventory.pluginengine.kot.CafeCartPunchPort.Cart;
import com.inventory.pluginengine.kot.CafeCartPunchPort.Delta;
import com.inventory.pluginengine.kot.CafeCartPunchPort.Punch;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotKind;
import com.inventory.plugins.cafe.domain.CafeKotLine;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import com.inventory.plugins.cafe.domain.CafeKotStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The punch service turns a reconciled cart into kitchen tickets, and makes an interrupted punch
 * recoverable.
 *
 * <p>The load-bearing property under test is that a replay NEVER reconciles again. The cart has
 * already advanced; a second reconcile would compute every delta as zero and silently drop the
 * tickets the kitchen is owed.
 *
 * <p>The cart itself is reached through {@link CafeCartPunchPort}, which {@code core/product}
 * implements; its document shape and shop scoping are tested there ({@code
 * CafeCartPunchPortImplTest}).
 */
class CafeKotPunchServiceTest {

  private CafeCartPunchPort cartPort;
  private CafeKotRepository kotRepository;
  private CafeSequenceService sequenceService;
  private CafeKotPunchService service;

  @BeforeEach
  void setUp() {
    cartPort = mock(CafeCartPunchPort.class);
    kotRepository = mock(CafeKotRepository.class);
    sequenceService = mock(CafeSequenceService.class);
    service = new CafeKotPunchService(cartPort, kotRepository, sequenceService);

    when(sequenceService.allocate(anyString(), any(LocalDate.class), any())).thenReturn(7);
    when(kotRepository.insert(any(CafeKot.class))).thenAnswer(i -> i.getArgument(0));
  }

  // ---------------------------------------------------------------- fixtures

  private static Delta delta(String ref, String name, String department, int quantity) {
    return new Delta(ref, name, department, null, quantity);
  }

  private static Punch punchDoc(boolean complete, List<Delta> deltas, List<String> kotIds) {
    return new Punch("punch-1", "key-1", complete, kotIds, deltas);
  }

  private static Optional<Cart> purchase(List<Punch> punches) {
    return dineInPurchase(punches, null, null);
  }

  private static Optional<Cart> dineInPurchase(
      List<Punch> punches, String tableLabel, String tokenNo) {
    return Optional.of(new Cart("p1", tableLabel, tokenNo, punches));
  }

  /** Like {@link #purchaseGainsPunchOnClaim} but the cart carries a table and a token. */
  private void dineInPurchaseGainsPunchOnClaim(Punch appended, String tableLabel, String tokenNo) {
    when(cartPort.findCart("shop-1", "p1"))
        .thenReturn(
            dineInPurchase(List.of(), tableLabel, tokenNo),
            dineInPurchase(List.of(appended), tableLabel, tokenNo));
    when(cartPort.claimAndReconcile(any(), any(), any(), any(), any())).thenReturn(true);
  }

  /** Every read of the purchase in this test sees exactly this punch already on the cart. */
  private void purchaseHasPunch(Punch punch) {
    when(cartPort.findCart("shop-1", "p1")).thenReturn(purchase(List.of(punch)));
  }

  /** No punch yet on the first read; after claimAndReconcile the pipeline has appended one. */
  private void purchaseGainsPunchOnClaim(Punch appended) {
    when(cartPort.findCart("shop-1", "p1"))
        .thenReturn(purchase(List.of()), purchase(List.of(appended)));
    when(cartPort.claimAndReconcile(any(), any(), any(), any(), any())).thenReturn(true);
  }

  /** Tickets are inserted one by one, never saved: an insert cannot overwrite a stored ticket. */
  private List<CafeKot> savedKots() {
    ArgumentCaptor<CafeKot> saved = ArgumentCaptor.forClass(CafeKot.class);
    verify(kotRepository, org.mockito.Mockito.atLeastOnce()).insert(saved.capture());
    verify(kotRepository, never()).saveAll(any());
    return saved.getAllValues();
  }

  private List<String> newlyCreatedKotIds() {
    return savedKots().stream().map(CafeKot::getId).toList();
  }

  /** The kotIds the punch was marked COMPLETE with, in this shop; fails unless marked once. */
  @SuppressWarnings("unchecked")
  private List<String> completedKotIds() {
    ArgumentCaptor<List<String>> kotIds = ArgumentCaptor.forClass(List.class);
    verify(cartPort).markPunchComplete(eq("shop-1"), eq("p1"), eq("punch-1"), kotIds.capture());
    return kotIds.getValue();
  }

  // ------------------------------------------------------------------- tests

  @Test
  void aFreshPunchReconcilesThenCreatesOneTicketPerStation() {
    purchaseGainsPunchOnClaim(
        punchDoc(
            false,
            List.of(delta("menu:m1", "Biryani", "KITCHEN", 2), delta("menu:m2", "Coke", "BAR", 1)),
            List.of()));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    verify(cartPort)
        .claimAndReconcile(eq("shop-1"), eq("p1"), anyString(), eq("key-1"), eq("user-1"));
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

    // And the punch is finished, in this shop, carrying the ids of the tickets that now exist.
    assertEquals(List.of("punch-1:KITCHEN:ISSUE", "punch-1:BAR:ISSUE"), completedKotIds());
  }

  @Test
  void aDineInCartsTicketCarriesItsTableAndToken() {
    dineInPurchaseGainsPunchOnClaim(
        punchDoc(
            false,
            List.of(delta("menu:m1", "Biryani", "KITCHEN", 2)),
            List.of()),
        "T4",
        "12");

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(1, kots.size());
    assertEquals("T4", kots.get(0).getTableLabel());
    assertEquals("12", kots.get(0).getTokenNo());
  }

  @Test
  void aNegativeDeltaBecomesACancelTicketCarryingTheAbsoluteQuantity() {
    purchaseGainsPunchOnClaim(
        punchDoc(
            false,
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
    purchaseGainsPunchOnClaim(punchDoc(false, List.of(), List.of()));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertTrue(kots.isEmpty());
    verify(kotRepository, never()).insert(any(CafeKot.class));
    verify(sequenceService, never()).allocate(anyString(), any(LocalDate.class), any());
    // The punch is still finished: nothing is owed, so nothing must be redriven later.
    assertEquals(List.of(), completedKotIds());
  }

  @Test
  void aNoneLineIsClaimedButPrintsNoTicket() {
    purchaseGainsPunchOnClaim(
        punchDoc(
            false,
            List.of(
                delta("inventory:coke", "Coca-Cola", "NONE", 1),
                delta("inventory:lassi", "Lassi", "BAR", 1),
                delta("menu:m1", "Biryani", "KITCHEN", 1)),
            List.of()));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(List.of("punch-1:BAR:ISSUE", "punch-1:KITCHEN:ISSUE"), newlyCreatedKotIds());
    assertTrue(
        kots.stream()
            .flatMap(k -> k.getLines().stream())
            .noneMatch(l -> "Coca-Cola".equals(l.getName())));
  }

  @Test
  void aPunchOfOnlyNoneLinesCompletesWithNoTicketAndNoNumber() {
    purchaseGainsPunchOnClaim(
        punchDoc(
            false,
            List.of(
                delta("inventory:coke", "Coca-Cola", "NONE", 2),
                delta("inventory:sprite", "Sprite", "NONE", -1)),
            List.of()));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertTrue(kots.isEmpty());
    verify(kotRepository, never()).insert(any(CafeKot.class));
    verify(sequenceService, never()).allocate(anyString(), any(LocalDate.class), any());
    assertEquals(List.of(), completedKotIds());
  }

  @Test
  void theStationFrozenOnTheLineWinsOverAnyLaterMenuEdit() {
    // Lassi was added before it was placed in a section, so its line carries KITCHEN. The punch
    // prints it there and never looks the station up again.
    purchaseGainsPunchOnClaim(
        punchDoc(
            false,
            List.of(delta("inventory:lassi", "Lassi", "KITCHEN", 1)),
            List.of()));

    service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(List.of("punch-1:KITCHEN:ISSUE"), newlyCreatedKotIds());
  }

  @Test
  void aTicketAConcurrentRecoveryAlreadyWroteIsReadBackNotOverwritten() {
    // Two recoveries of one PENDING punch can both find the ticket missing. The second must not
    // overwrite the first: that renumbers paper a cook may already hold and resets reprintCount.
    purchaseGainsPunchOnClaim(
        punchDoc(
            false,
            List.of(delta("menu:m1", "Biryani", "KITCHEN", 2)),
            List.of()));
    CafeKot written = new CafeKot();
    written.setId("punch-1:KITCHEN:ISSUE");
    written.setKotNo(3);
    written.setReprintCount(1);
    when(kotRepository.insert(any(CafeKot.class)))
        .thenThrow(new org.springframework.dao.DuplicateKeyException("E11000 duplicate key"));
    when(kotRepository.findById("punch-1:KITCHEN:ISSUE")).thenReturn(Optional.of(written));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(1, kots.size());
    assertEquals(3, kots.get(0).getKotNo(), "the stored ticket keeps its number");
    assertEquals(1, kots.get(0).getReprintCount(), "and its reprint count");
    verify(kotRepository, never()).saveAll(any());
  }

  @Test
  void aCompleteReplayReturnsTheRecordedTicketsAndCreatesNothing() {
    CafeKot existing = new CafeKot();
    existing.setId("punch-1:KITCHEN:ISSUE");
    purchaseHasPunch(
        punchDoc(
            true,
            List.of(delta("menu:m1", "Biryani", "KITCHEN", 2)),
            List.of("punch-1:KITCHEN:ISSUE")));
    when(kotRepository.findByShopIdAndPunchId("shop-1", "punch-1")).thenReturn(List.of(existing));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(List.of("punch-1:KITCHEN:ISSUE"), kots.stream().map(CafeKot::getId).toList());
    verify(cartPort, never()).claimAndReconcile(any(), any(), any(), any(), any());
    verify(kotRepository, never()).insert(any(CafeKot.class));
    verify(cartPort, never()).markPunchComplete(any(), any(), any(), any());
  }

  @Test
  void aPendingReplayFinishesFromTheStoredDeltasAndNeverReconcilesAgain() {
    Delta kitchen = delta("menu:m1", "Biryani", "KITCHEN", 2);
    Delta bar = delta("menu:m2", "Coke", "BAR", 1);
    // KITCHEN already written by the attempt that died.
    purchaseHasPunch(
        punchDoc(false, List.of(kitchen, bar), List.of("punch-1:KITCHEN:ISSUE")));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    // The cart advanced during the attempt that died. Reconciling again would compute
    // every delta as zero and silently drop the food the kitchen is owed.
    verify(cartPort, never()).claimAndReconcile(any(), any(), any(), any(), any());
    assertEquals(List.of("punch-1:BAR:ISSUE"), newlyCreatedKotIds());

    // Only the missing ticket gets a number; the KITCHEN ticket keeps the one it was printed with.
    verify(sequenceService, times(1)).allocate(eq("shop-1"), any(LocalDate.class), eq(CafeSequenceSeries.KOT));

    // The punch now records every ticket of the punch, not just the one written this time.
    assertEquals(List.of("punch-1:KITCHEN:ISSUE", "punch-1:BAR:ISSUE"), completedKotIds());
    assertEquals(1, kots.size(), "only the ticket written here is returned when the rest are absent");
  }

  @Test
  void aCrashAfterSaveButBeforeMarkCompleteKeepsTheSurvivingTicketsNumber() {
    // The exact window this task exists for: an earlier attempt's saveAll (:162) landed the
    // KITCHEN ticket in the repository, but the process died before markComplete (:174) ever
    // recorded it in kotIds. kotIds is therefore still empty on this replay, even though the
    // ticket — with a kotNo already printed and in the kitchen's hands — exists.
    Delta kitchen = delta("menu:m1", "Biryani", "KITCHEN", 2);
    Delta bar = delta("menu:m2", "Coke", "BAR", 1);
    purchaseHasPunch(punchDoc(false, List.of(kitchen, bar), List.of()));
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
    when(cartPort.findCart("shop-1", "p1"))
        .thenReturn(
            purchase(List.of()),
            purchase(
                List.of(
                    punchDoc(
                        false,
                        List.of(delta("menu:m2", "Coke", "BAR", 1)),
                        List.of()))));
    when(cartPort.claimAndReconcile(any(), any(), any(), any(), any())).thenReturn(false);

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(List.of("punch-1:BAR:ISSUE"), kots.stream().map(CafeKot::getId).toList());
  }

  @Test
  void anAbsentCartIsNotFound() {
    when(cartPort.findCart("shop-1", "p1")).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class, () -> service.punch("shop-1", "user-1", "p1", "key-1"));
    verify(cartPort, never()).claimAndReconcile(any(), any(), any(), any(), any());
  }

  @Test
  void aClaimThatLandsNoPunchOnAnExistingCartIsAnError() {
    when(cartPort.findCart("shop-1", "p1"))
        .thenReturn(purchase(List.of()), purchase(List.of()));
    when(cartPort.claimAndReconcile(any(), any(), any(), any(), any())).thenReturn(false);

    assertThrows(
        ValidationException.class, () -> service.punch("shop-1", "user-1", "p1", "key-1"));
    verify(kotRepository, never()).insert(any(CafeKot.class));
  }

  @Test
  void anIdempotencyKeyIsRequired() {
    assertThrows(
        ValidationException.class, () -> service.punch("shop-1", "user-1", "p1", "  "));
    verifyNoInteractions(cartPort, kotRepository);
  }

  @Test
  void aZeroDeltaContributesNothing() {
    purchaseGainsPunchOnClaim(
        punchDoc(
            false,
            List.of(delta("menu:m1", "Biryani", "KITCHEN", 0), delta("menu:m2", "Coke", "BAR", 1)),
            List.of()));

    service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(List.of("punch-1:BAR:ISSUE"), newlyCreatedKotIds());
  }

  @Test
  void everyPurchaseReadIsScopedByShop() {
    purchaseHasPunch(punchDoc(true, List.of(), List.of()));

    service.punch("shop-1", "user-1", "p1", "key-1");

    // The shop scoping of the read itself is pinned in CafeCartPunchPortImplTest.
    verify(cartPort).findCart("shop-1", "p1");
  }

  @Test
  void aLineNoteReachesTheTicket() {
    // The numeric width of the stored quantity is CafeCartPunchPortImplTest's concern.
    Delta noted = new Delta("menu:m1", "Biryani", "KITCHEN", "extra spicy", 2);
    purchaseGainsPunchOnClaim(punchDoc(false, List.of(noted), List.of()));

    List<CafeKot> kots = service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(2, kots.get(0).getLines().get(0).getQuantity());
    assertEquals("extra spicy", kots.get(0).getLines().get(0).getNote());
  }

  @Test
  void aBlankDepartmentFallsBackToTheDefaultStation() {
    purchaseGainsPunchOnClaim(
        punchDoc(false, List.of(delta("menu:m1", "Biryani", null, 1)), List.of()));

    service.punch("shop-1", "user-1", "p1", "key-1");

    assertEquals(List.of("punch-1:KITCHEN:ISSUE"), newlyCreatedKotIds());
  }

  @Test
  void ticketNumbersAreAllocatedOncePerCreatedTicket() {
    purchaseGainsPunchOnClaim(
        punchDoc(
            false,
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
    Punch earlier = new Punch("punch-0", "key-0", true, List.of(), List.of());
    when(cartPort.findCart("shop-1", "p1"))
        .thenReturn(
            purchase(List.of(earlier)),
            purchase(
                List.of(
                    earlier,
                    punchDoc(
                        false,
                        List.of(delta("menu:m2", "Coke", "BAR", 1)),
                        List.of()))));
    when(cartPort.claimAndReconcile(any(), any(), any(), any(), any())).thenReturn(true);

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
            false,
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
