package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.integration.ShopMenuLookup;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.plugins.cafe.domain.CafeTab;
import com.inventory.plugins.cafe.domain.CafeTabLine;
import com.inventory.plugins.cafe.domain.CafeTabStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mirrors {@code QuotationService}'s open-quotation rules exactly: scoped to shop and user, a
 * hard cap that refuses rather than evicts, no expiry/TTL/rollover, and closes only explicitly.
 *
 * <p>B2: and every edit is a targeted write. Against {@link InMemoryCafeTabs} rather than a
 * {@code verify()}, because the question is not whether a method was called — it is whether the
 * {@code pendingFlush} record {@link CafeTabFlusher}'s claim put on the tab is still there after
 * an ordinary CRUD edit that read the tab before the claim and wrote it after.
 *
 * <p>Reverting any of the four edits in {@code CafeTabService} to
 * {@code cafeTabRepository.save(tab)} fails
 * {@link #composingTheNextItemMidFlushLeavesTheClaimedRoundClaimed},
 * {@link #editingALineMidFlushLeavesTheClaimedRoundClaimed},
 * {@link #removingALineMidFlushLeavesTheClaimedRoundClaimed} or
 * {@link #closingATabMidFlushLeavesTheClaimedRoundClaimed} by name.
 */
class CafeTabServiceTest {

  private static final String SHOP_ID = "shop-1";
  private static final String USER_ID = "user-1";
  private static final String OTHER_USER_ID = "user-2";
  private static final String TAB_ID = "tab-1";

  private InMemoryCafeTabs tabs;
  private CafeTokenService cafeTokenService;
  private ShopMenuLookup shopMenuLookup;
  private CafeTabService service;

  @BeforeEach
  void setUp() {
    tabs = new InMemoryCafeTabs();
    cafeTokenService = mock(CafeTokenService.class);
    shopMenuLookup = mock(ShopMenuLookup.class);
    service =
        new CafeTabService(
            tabs.repository(),
            cafeTokenService,
            shopMenuLookup,
            new CafeTabTargetedWriter(tabs.mongoTemplate()));
  }

  // ------------------------------------------------- the open-quotation rules

  @Test
  void aTabIsScopedToTheCashierWhoOpenedIt() {
    tabs.seed(openTab(TAB_ID));

    assertTrue(service.list(SHOP_ID, OTHER_USER_ID).isEmpty());

    // Another user's id cannot load the tab by id either.
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.addLine(SHOP_ID, OTHER_USER_ID, TAB_ID, "menu:tea", 1, null));
  }

  @Test
  void theThirtyFirstOpenTabIsRefusedRatherThanEvictingTheOldest() {
    for (int i = 0; i < CafeTabService.MAX_OPEN_TABS_PER_USER; i++) {
      tabs.seed(openTab("tab-" + i));
    }

    assertThrows(ValidationException.class, () -> service.open(SHOP_ID, USER_ID));

    assertEquals(
        CafeTabService.MAX_OPEN_TABS_PER_USER,
        service.list(SHOP_ID, USER_ID).size(),
        "nothing was evicted to make room");
    verify(cafeTokenService, never()).allocateToken(anyString(), anyString());
  }

  @Test
  void aTabOpenedYesterdayIsStillOpenTodayWithItsOriginalToken() {
    CafeTab old = openTab("tab-old");
    old.setTokenNo("7");
    old.setCreatedAt(Instant.now().minusSeconds(60L * 60 * 30));
    old.setUpdatedAt(old.getCreatedAt());
    tabs.seed(old);

    List<CafeTab> open = service.list(SHOP_ID, USER_ID);

    assertEquals(1, open.size());
    assertEquals(CafeTabStatus.OPEN, open.get(0).getStatus());
    assertEquals("7", open.get(0).getTokenNo());
    verify(cafeTokenService, never()).allocateToken(anyString(), anyString());
  }

  @Test
  void closingATabIsTheOnlyWayItLeavesTheOpenState() {
    tabs.seed(openTab(TAB_ID));

    service.close(SHOP_ID, USER_ID, TAB_ID);

    assertEquals(CafeTabStatus.CLOSED, tabs.read(TAB_ID).getStatus());

    // Once closed, it can no longer be mutated as an open tab.
    assertThrows(
        ValidationException.class,
        () -> service.addLine(SHOP_ID, USER_ID, TAB_ID, "menu:tea", 1, null));
  }

  @Test
  void everyTabReadIsScopedByShopAndUser() {
    tabs.seed(openTab(TAB_ID));

    service.list(SHOP_ID, USER_ID);
    verify(tabs.repository())
        .findByShopIdAndUserIdAndStatusOrderByCreatedAtDesc(SHOP_ID, USER_ID, CafeTabStatus.OPEN);

    assertThrows(
        ResourceNotFoundException.class, () -> service.close("other-shop", USER_ID, TAB_ID));
    assertThrows(
        ResourceNotFoundException.class, () -> service.close(SHOP_ID, OTHER_USER_ID, TAB_ID));
  }

  @Test
  void aComposedLineFreezesThePriceTheCustomerWasQuoted() {
    tabs.seed(openTab(TAB_ID));
    MenuItem tea = menuItem();

    CafeTab afterAdd = service.addLine(SHOP_ID, USER_ID, TAB_ID, "menu:tea", 2, null);

    CafeTabLine line = afterAdd.getLines().get(0);
    assertEquals(new BigDecimal("30.00"), line.getPrice(), "what the customer was quoted");
    assertEquals("2.5", line.getCgst());
    assertEquals("2.5", line.getSgst());
    assertEquals("KITCHEN", line.getDepartment(), "and the station, as before");

    // The menu moves on mid-round.
    tea.setSellingPrice(new BigDecimal("50.00"));
    assertEquals(
        new BigDecimal("30.00"),
        tabs.read(TAB_ID).getLines().get(0).getPrice(),
        "the composed line is not re-priced under the customer");

    CafeTab afterEdit = service.updateLine(SHOP_ID, USER_ID, TAB_ID, line.getLineRef(), 3, null);
    assertEquals(
        new BigDecimal("30.00"), afterEdit.getLines().get(0).getPrice(), "nor by an edit");
    assertEquals(3, afterEdit.getLines().get(0).getQuantity());
  }

  @Test
  void openAllocatesATokenUnderTheTabScopeDistinctFromTheBillScope() {
    when(cafeTokenService.allocateToken(SHOP_ID, "TAB")).thenReturn("1");

    CafeTab tab = service.open(SHOP_ID, USER_ID);

    assertEquals("1", tab.getTokenNo());
    assertEquals(CafeTabStatus.OPEN, tab.getStatus());
    assertTrue(tab.getLines().isEmpty());
    verify(cafeTokenService).allocateToken(SHOP_ID, "TAB");
    verify(cafeTokenService, never()).allocateToken(SHOP_ID, CafeTokenService.SCOPE_BILL);
  }

  // ------------------------------------ B2: a CRUD edit interleaved with a claim

  @Test
  void composingTheNextItemMidFlushLeavesTheClaimedRoundClaimed() {
    // The headline flow. The cashier presses Print KOT; the claim empties the tab and records the
    // PENDING flush; while the append, totals and ticket round-trips are in the air, the cashier
    // composes the next item. That edit read the tab BEFORE the claim and writes it after.
    seedTabWithTea();
    menuItem();
    tabs.interleave(this::theClaimLands);

    service.addLine(SHOP_ID, USER_ID, TAB_ID, "menu:tea", 1, null);

    assertTheClaimSurvived();
    List<CafeTabLine> lines = tabs.read(TAB_ID).getLines();
    assertEquals(1, lines.size(), "only the newly composed line is on the tab");
    assertEquals(1, lines.get(0).getQuantity(), "the claimed round is not back under it");
    assertFalse(tabs.fullReplaceUsed(), "and it got there without replacing the document");
  }

  @Test
  void editingALineMidFlushLeavesTheClaimedRoundClaimed() {
    seedTabWithTea();
    tabs.interleave(this::theClaimLands);

    service.updateLine(SHOP_ID, USER_ID, TAB_ID, "l1", 5, null);

    assertTheClaimSurvived();
    assertTrue(
        tabs.read(TAB_ID).getLines().isEmpty(),
        "the claimed line is not put back by an edit aimed at it");
  }

  @Test
  void removingALineMidFlushLeavesTheClaimedRoundClaimed() {
    seedTabWithTea();
    tabs.interleave(this::theClaimLands);

    service.removeLine(SHOP_ID, USER_ID, TAB_ID, "l1");

    assertTheClaimSurvived();
    assertTrue(tabs.read(TAB_ID).getLines().isEmpty());
  }

  @Test
  void closingATabMidFlushLeavesTheClaimedRoundClaimed() {
    // A close must not delete the record either: the flush's resume path is the only thing that
    // can still finish the round, and it reads pendingFlush off this document.
    seedTabWithTea();
    tabs.interleave(this::theClaimLands);

    service.close(SHOP_ID, USER_ID, TAB_ID);

    assertTheClaimSurvived();
    assertEquals(CafeTabStatus.CLOSED, tabs.read(TAB_ID).getStatus());
  }

  @Test
  void anEditOfATabClosedUnderneathItChangesNothing() {
    // The targeted write requires the tab to still be OPEN at the moment it lands, not merely
    // when it was read -- so an edit racing a close is refused rather than reopening the tab.
    seedTabWithTea();
    menuItem();
    tabs.interleave(
        () ->
            tabs.mutateStored(
                TAB_ID, stored -> stored.put("status", CafeTabStatus.CLOSED.name())));

    assertThrows(
        ValidationException.class,
        () -> service.addLine(SHOP_ID, USER_ID, TAB_ID, "menu:tea", 1, null));

    assertEquals(CafeTabStatus.CLOSED, tabs.read(TAB_ID).getStatus());
    assertEquals(
        1, tabs.read(TAB_ID).getLines().size(), "nothing was appended to a closed tab");
  }

  // ------------------------------------------------------------------- helpers

  /** The claim, reaching the stored document exactly as {@link CafeTabFlusher} leaves it. */
  private void theClaimLands() {
    tabs.mutateStored(
        TAB_ID,
        stored -> {
          List<Document> claimed = new ArrayList<>(stored.getList("lines", Document.class));
          stored.put(
              "pendingFlush",
              new Document("flushId", "f1")
                  .append("idempotencyKey", "k1")
                  .append("targetPurchaseId", "bill-7")
                  .append("status", "PENDING")
                  .append("lines", claimed));
          stored.put("lines", new ArrayList<Document>());
          stored.put(
              "recentFlushKeys",
              new ArrayList<>(
                  List.of(new Document("idempotencyKey", "k1").append("flushId", "f1"))));
        });
  }

  /**
   * The record the flush is owed, and the key that stops the same round being sent twice, exactly
   * as the claim left them. A full-document replace from the pre-claim snapshot nulls the first
   * and rewinds the second, and the next Print KOT then sends the same food again under a fresh
   * flush id.
   */
  private void assertTheClaimSurvived() {
    Document stored = tabs.stored(TAB_ID);
    Document pending = (Document) stored.get("pendingFlush");
    assertNotNull(pending, "the flush's recovery record is the only evidence the round exists");
    assertEquals("f1", pending.getString("flushId"));
    assertEquals("PENDING", pending.getString("status"));
    assertEquals(
        1,
        pending.getList("lines", Document.class).size(),
        "with the claimed lines still on it, for the resume to send");
    assertEquals(
        1,
        stored.getList("recentFlushKeys", Document.class).size(),
        "and the key that refuses a second claim under it");
  }

  private void seedTabWithTea() {
    CafeTab tab = openTab(TAB_ID);
    CafeTabLine tea = new CafeTabLine();
    tea.setLineRef("l1");
    tea.setSellableRef("menu:tea");
    tea.setName("Tea");
    tea.setQuantity(2);
    tea.setDepartment("KITCHEN");
    tea.setPrice(new BigDecimal("30.00"));
    tab.setLines(new ArrayList<>(List.of(tea)));
    tabs.seed(tab);
  }

  private MenuItem menuItem() {
    MenuItem tea = new MenuItem();
    tea.setId("tea");
    tea.setName("Tea");
    tea.setSellingPrice(new BigDecimal("30.00"));
    tea.setCgst("2.5");
    tea.setSgst("2.5");
    tea.setDepartment("kitchen");
    when(shopMenuLookup.findMenuItem(SHOP_ID, "tea")).thenReturn(Optional.of(tea));
    return tea;
  }

  private static CafeTab openTab(String id) {
    CafeTab tab = new CafeTab();
    tab.setId(id);
    tab.setShopId(SHOP_ID);
    tab.setUserId(USER_ID);
    tab.setTokenNo("1");
    tab.setStatus(CafeTabStatus.OPEN);
    tab.setLines(new ArrayList<>());
    tab.setRecentFlushKeys(new ArrayList<>());
    tab.setCreatedAt(Instant.now());
    tab.setUpdatedAt(Instant.now());
    return tab;
  }
}
