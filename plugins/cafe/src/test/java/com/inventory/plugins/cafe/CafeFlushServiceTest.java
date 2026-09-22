package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.cart.CartTotalsPort;
import com.inventory.pluginengine.integration.ShopMenuLookup;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.plugins.cafe.domain.CafeFlushStatus;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotKind;
import com.inventory.plugins.cafe.domain.CafeKotLine;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import com.inventory.plugins.cafe.domain.CafeKotStatus;
import com.inventory.plugins.cafe.domain.CafePendingFlush;
import com.inventory.plugins.cafe.domain.CafeRecentFlush;
import com.inventory.plugins.cafe.domain.CafeTab;
import com.inventory.plugins.cafe.domain.CafeTabLine;
import com.inventory.plugins.cafe.domain.CafeTabRepository;
import com.inventory.plugins.cafe.domain.CafeTabStatus;
import com.mongodb.client.result.UpdateResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.slf4j.LoggerFactory;
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
  private ShopMenuLookup menuLookup;
  private CartTotalsPort cartTotalsPort;
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
    menuLookup = mock(ShopMenuLookup.class);
    cartTotalsPort = mock(CartTotalsPort.class);

    // The menu the tab was composed from: Tea 30.00 at 2.5+2.5, Beer 120.00 untaxed.
    when(menuLookup.findMenuItem(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              String menuItemId = invocation.getArgument(1);
              return Optional.ofNullable(MENU.get(menuItemId));
            });

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
            tokenService,
            menuLookup,
            cartTotalsPort);
  }

  private static final Map<String, MenuItem> MENU =
      Map.of(
          "tea", menuItem("tea", "Tea", "30.00", "2.5", "2.5"),
          "beer", menuItem("beer", "Beer", "120.00", null, null));

  private static MenuItem menuItem(
      String id, String name, String price, String cgst, String sgst) {
    MenuItem item = new MenuItem();
    item.setId(id);
    item.setName(name);
    item.setSellingPrice(new BigDecimal(price));
    item.setAvailable(true);
    item.setCgst(cgst);
    item.setSgst(sgst);
    return item;
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
  void aSecondCallWithTheSameKeyReplaysRatherThanFlushingAgain() {
    fake.tab = openTabWithLines();
    fake.purchase(BILL_ID, null);

    // The same key twice in sequence: a replay. Genuine concurrency — two keys interleaved — is
    // aSecondKeyCannotClaimATabThatStillOwesAPendingFlush, below.
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


  // ------------------------------------------------- what actually lands on the bill

  @Test
  void flushedLinesCarryTheMenusPriceAndTaxOntoTheBill() {
    fake.tab = openTabWithLines();
    fake.purchase(BILL_ID, null);

    service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    Document tea = fake.items(BILL_ID).get(0);
    assertEquals(new BigDecimal("2"), tea.get("quantity"), "two teas, not an unpriced zero");
    assertEquals(2, tea.get("baseQuantity"));
    assertEquals(new BigDecimal("30.00"), tea.get("priceToRetail"), "the menu's selling price");
    assertEquals(new BigDecimal("30.00"), tea.get("maximumRetailPrice"));
    assertEquals("2.5", tea.get("cgst"));
    assertEquals("2.5", tea.get("sgst"));
    // 30.00 x 2 = 60.00, plus 2.5% CGST and 2.5% SGST = 63.00. The shop is paid for the round.
    assertEquals(new BigDecimal("63.00"), tea.get("totalAmount"));
    assertEquals(BigDecimal.ZERO, tea.get("discount"));
    assertEquals("PCS", tea.get("saleUnit"));
    assertEquals(1, tea.get("unitFactor"));
    assertEquals("REGULAR", tea.get("billingMode"));

    Document beer = fake.items(BILL_ID).get(1);
    assertEquals(new BigDecimal("120.00"), beer.get("priceToRetail"));
    assertEquals(new BigDecimal("120.00"), beer.get("totalAmount"), "no rate, no tax added");
  }

  @Test
  void theBillsTotalsAreRecomputedAfterTheLinesAreAppended() {
    fake.tab = openTabWithLines();
    fake.purchase(BILL_ID, null);

    service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    // Checkout settles against the STORED grandTotal and nothing else recomputes it, so the
    // flush has to ask. Priced lines under an untouched zero total are still free food.
    verify(cartTotalsPort, times(1)).recalculateTotals(SHOP_ID, BILL_ID);
  }

  @Test
  void twoTabLinesOfOneMenuItemStayIndividuallyAddressableOnTheBill() {
    CafeTab tab = baseTab();
    tab.setLines(
        new ArrayList<>(
            List.of(
                line("l1", "Tea", 2, "KITCHEN", "no sugar"),
                line("l2", "Tea", 1, "KITCHEN", "extra hot"))));
    fake.tab = tab;
    fake.purchase(BILL_ID, null);

    service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    List<Document> billLines = fake.items(BILL_ID);
    assertEquals(2, billLines.size(), "one bill line per composed line; nothing is merged");
    assertEquals(
        List.of("menu:tea", "menu:tea"),
        billLines.stream().map(d -> d.getString("sellableRef")).toList(),
        "sellableRef names the item, so it cannot tell these two apart");
    assertEquals(
        List.of("l1", "l2"),
        billLines.stream().map(d -> d.getString("lineRef")).toList(),
        "lineRef does: whoever reduces the second owes the kitchen ITS note and quantity");
    assertEquals("no sugar", billLines.get(0).getString("note"));
    assertEquals("extra hot", billLines.get(1).getString("note"));
  }

  @Test
  void theTicketCarriesEachLinesQuantity() {
    fake.tab = openTabWithLines();
    fake.purchase(BILL_ID, null);

    List<CafeKot> tickets = service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    CafeKot kitchen =
        tickets.stream().filter(k -> "KITCHEN".equals(k.getDepartment())).findFirst().orElseThrow();
    assertEquals(1, kitchen.getLines().size());
    assertEquals("Tea", kitchen.getLines().get(0).getName());
    assertEquals(2, kitchen.getLines().get(0).getQuantity(), "the cook makes two, not zero");
  }

  @Test
  void aNewBillOpensWithZeroMoneyRatherThanNone() {
    fake.tab = openTabWithLines();

    service.flush(SHOP_ID, USER_ID, TAB_ID, null, KEY);

    Document bill = fake.onlyPurchase();
    assertEquals(BigDecimal.ZERO, bill.get("subTotal"));
    assertEquals(BigDecimal.ZERO, bill.get("taxTotal"));
    assertEquals(BigDecimal.ZERO, bill.get("sgstAmount"));
    assertEquals(BigDecimal.ZERO, bill.get("cgstAmount"));
    assertEquals(BigDecimal.ZERO, bill.get("discountTotal"));
    assertEquals(BigDecimal.ZERO, bill.get("saleAdditionalDiscountTotal"));
    assertEquals(BigDecimal.ZERO, bill.get("grandTotal"), "an open bill has a total of zero, not none");
  }

  @Test
  void aSecondFlushOntoTheSameBillIsRoundTwo() {
    fake.tab = openTabWithLines();
    fake.purchase(BILL_ID, null);

    List<CafeKot> first = service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);
    assertEquals(1, first.get(0).getRoundNo());

    fake.tab.setLines(new ArrayList<>(List.of(line("l3", "Tea", 1, "KITCHEN", null))));
    List<CafeKot> second = service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, "idem-2");

    assertEquals(2, fake.flushIds(BILL_ID).size(), "the bill remembers both flushes");
    assertEquals(
        2,
        second.get(0).getRoundNo(),
        "the round is the flush's position on the bill; a forgotten list prints Round 1 twice");
  }

  // ---------------------------------------------------------- one tab, two keys

  @Test
  void aSecondKeyCannotClaimATabThatStillOwesAPendingFlush() {
    fake.tab = openTabWithLines();
    fake.purchase(BILL_ID, null);

    // The interleaving the guard above the claim cannot see: this flush read a tab owing
    // nothing, and another key claimed in the gap before its own claim reached the server.
    fake.beforeClaim =
        f -> {
          f.tab.setPendingFlush(
              pendingFlush("flush-1", "idem-other", CafeFlushStatus.PENDING, claimedLines()));
          f.tab.setLines(new ArrayList<>());
        };

    assertThrows(
        ValidationException.class,
        () -> service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY),
        "nothing was claimed, so the caller is told to retry");

    CafePendingFlush owed = fake.tab.getPendingFlush();
    assertEquals("idem-other", owed.getIdempotencyKey(), "the other key's record is untouched");
    assertEquals(
        2,
        owed.getLines().size(),
        "and still holds the lines it owes a kitchen that has not been told");
    assertEquals(0, fake.appendCount, "nothing reached the bill");
    assertTrue(kotStore.isEmpty(), "and nothing reached the kitchen");
  }

  @Test
  void aTabOwingAnEarlierFlushFinishesItBeforeClaimingTheNewRound() {
    fake.tab = baseTab();
    fake.tab.setPendingFlush(
        pendingFlush("flush-1", "idem-old", CafeFlushStatus.PENDING, claimedLines()));
    // The cashier composed another round while the earlier flush was still owed.
    fake.tab.setLines(new ArrayList<>(List.of(line("l3", "Tea", 3, "KITCHEN", null))));
    fake.purchase(BILL_ID, null);

    List<CafeKot> round2 = service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    assertEquals(3, kotStore.size(), "the owed flush's two tickets, then this round's one");
    assertEquals(3, fake.items(BILL_ID).size(), "both rounds' lines are on the bill");
    assertEquals(2, fake.flushIds(BILL_ID).size());
    assertEquals(1, round2.size(), "the caller gets the round it asked for");
    assertEquals(2, round2.get(0).getRoundNo(), "and it is the second round on this bill");
    assertEquals(CafeFlushStatus.COMPLETE, fake.tab.getPendingFlush().getStatus());
  }

  @Test
  void aStaleKeyFromTwoFlushesAgoReturnsItsOwnTicketsAndClaimsNothing() {
    fake.tab = openTabWithLines();
    fake.purchase(BILL_ID, null);

    List<CafeKot> round1 = service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    fake.tab.setLines(new ArrayList<>(List.of(line("l3", "Tea", 1, "KITCHEN", null))));
    service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, "idem-2");

    // Round three is composed, and the client replays the key it parked two flushes ago.
    fake.tab.setLines(new ArrayList<>(List.of(line("l4", "Beer", 4, "BAR", null))));
    List<CafeKot> replayed = service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);

    assertEquals(ids(round1), ids(replayed), "the tickets that key created, not somebody else's");
    assertEquals(2, fake.appendCount, "round three is not appended to the bill");
    assertEquals(
        List.of("Beer"),
        fake.tab.getLines().stream().map(CafeTabLine::getName).toList(),
        "and is still sitting on the tab, unsent, where the cashier left it");
  }

  @Test
  void markCompleteSaysSoWhenItsRecordWasReplacedUnderneathIt() {
    fake.tab = openTabWithLines();
    fake.purchase(BILL_ID, null);
    // Somebody replaces this flush's record between its last ticket and its COMPLETE.
    fake.beforeMarkComplete = f -> f.tab.getPendingFlush().setFlushId("somebody-else");

    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    Logger logger = (Logger) LoggerFactory.getLogger(CafeFlushService.class);
    appender.start();
    logger.addAppender(appender);
    try {
      service.flush(SHOP_ID, USER_ID, TAB_ID, BILL_ID, KEY);
    } finally {
      logger.detachAppender(appender);
    }

    assertTrue(
        appender.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .anyMatch(event -> event.getFormattedMessage().contains("found no record to complete")),
        "a zero matched count is the one case where the invariant is already broken");
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

  private static CafePendingFlush pendingFlush(
      String flushId, String idempotencyKey, CafeFlushStatus status, List<CafeTabLine> lines) {
    CafePendingFlush pending = new CafePendingFlush();
    pending.setFlushId(flushId);
    pending.setIdempotencyKey(idempotencyKey);
    pending.setLines(lines);
    pending.setTargetPurchaseId(BILL_ID);
    pending.setStatus(status);
    return pending;
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
    copy.setRecentFlushKeys(
        source.getRecentFlushKeys() == null
            ? new ArrayList<>()
            : new ArrayList<>(source.getRecentFlushKeys()));
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

    /** Runs inside findAndModify, i.e. AFTER the service read the tab and BEFORE it claims. */
    private Consumer<FakeMongo> beforeClaim;

    /** Runs inside the tab updateFirst, i.e. after the tickets and before COMPLETE lands. */
    private Consumer<FakeMongo> beforeMarkComplete;

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

    Document onlyPurchase() {
      assertEquals(1, purchases.size(), "exactly one bill was opened");
      return purchases.values().iterator().next();
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
                  // $setOnInsert carries immutable empty lists; the server stores arrays that
                  // the append can then $push onto.
                  insert.put("items", new ArrayList<Document>());
                  insert.put("cafeFlushIds", new ArrayList<String>());
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
      if (beforeClaim != null) {
        Consumer<FakeMongo> hook = beforeClaim;
        beforeClaim = null;
        hook.accept(this);
      }
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
      String recordedStatus =
          tab.getPendingFlush() == null || tab.getPendingFlush().getStatus() == null
              ? null
              : tab.getPendingFlush().getStatus().name();
      if (!matches(q.get("pendingFlush.status"), recordedStatus)) {
        return null;
      }
      if (!matchesNin(
          q.get("recentFlushKeys.idempotencyKey"),
          tab.getRecentFlushKeys() == null
              ? List.of()
              : tab.getRecentFlushKeys().stream()
                  .map(CafeRecentFlush::getIdempotencyKey)
                  .toList())) {
        return null;
      }

      CafeTab preImage = copy(tab);

      // The update is an aggregation pipeline, and its stages run in order. Applying them in
      // order here is what makes the stage ORDER observable: a recovery log written after the
      // lines are emptied captures an empty array, exactly as the server would.
      List<Document> pipeline = (List<Document>) update.getUpdateObject().get("");
      List<CafeTabLine> lines = new ArrayList<>(tab.getLines());
      CafePendingFlush pending = tab.getPendingFlush();
      List<CafeRecentFlush> recent =
          tab.getRecentFlushKeys() == null
              ? new ArrayList<>()
              : new ArrayList<>(tab.getRecentFlushKeys());
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
        if (set.containsKey("recentFlushKeys")) {
          recent = applySlicedAppend((Document) set.get("recentFlushKeys"), recent);
        }
      }
      tab.setLines(lines);
      tab.setPendingFlush(pending);
      tab.setRecentFlushKeys(recent);
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
      if (beforeMarkComplete != null) {
        Consumer<FakeMongo> hook = beforeMarkComplete;
        beforeMarkComplete = null;
        hook.accept(this);
      }
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

    /** {@code $nin}: the document matches when none of its values is in the excluded list. */
    private boolean matchesNin(Object clause, List<String> actual) {
      if (clause == null) {
        return true;
      }
      Document nin = (Document) clause;
      List<?> excluded = (List<?>) nin.get("$nin");
      return excluded.stream().noneMatch(actual::contains);
    }

    /** {@code $slice} over {@code $concatArrays}, the way the claim bounds recentFlushKeys. */
    private List<CafeRecentFlush> applySlicedAppend(
        Document slice, List<CafeRecentFlush> current) {
      List<?> args = (List<?>) slice.get("$slice");
      Document concat = (Document) args.get(0);
      int keep = Math.abs(((Number) args.get(1)).intValue());
      List<?> parts = (List<?>) concat.get("$concatArrays");
      List<CafeRecentFlush> out = new ArrayList<>(current);
      for (Object appended : (List<?>) parts.get(1)) {
        Document entry = (Document) appended;
        CafeRecentFlush fresh = new CafeRecentFlush();
        fresh.setIdempotencyKey(entry.getString("idempotencyKey"));
        fresh.setFlushId(entry.getString("flushId"));
        out.add(fresh);
      }
      return out.size() <= keep ? out : new ArrayList<>(out.subList(out.size() - keep, out.size()));
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
