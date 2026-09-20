package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.integration.ShopMenuLookup;
import com.inventory.pluginengine.menu.MenuItem;
import com.inventory.pluginengine.order.KotView;
import com.inventory.pluginengine.order.PunchCommand;
import com.inventory.pluginengine.order.PunchLine;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotStatus;
import com.inventory.plugins.cafe.domain.CafeOrder;
import com.inventory.plugins.cafe.domain.CafeOrderPunch;
import com.inventory.plugins.cafe.domain.CafeOrderStatus;
import com.inventory.plugins.cafe.domain.CafePunchStatus;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import com.inventory.plugins.cafe.domain.CafeOrderPunchRepository;
import com.inventory.plugins.cafe.domain.CafeOrderRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

class CafeRunningOrderStorePunchTest {

  private CafeOrderRepository orderRepository;
  private CafeKotRepository kotRepository;
  private CafeOrderPunchRepository punchRepository;
  private CafeSequenceService sequenceService;
  private ShopMenuLookup menuLookup;
  private CafeRunningOrderStore store;
  private CafeOrder order;

  @BeforeEach
  void setUp() {
    orderRepository = mock(CafeOrderRepository.class);
    kotRepository = mock(CafeKotRepository.class);
    punchRepository = mock(CafeOrderPunchRepository.class);
    sequenceService = mock(CafeSequenceService.class);
    menuLookup = mock(ShopMenuLookup.class);
    store =
        new CafeRunningOrderStore(
            orderRepository, kotRepository, punchRepository, sequenceService, menuLookup);

    order = new CafeOrder();
    order.setId("order-1");
    order.setShopId("shop-1");
    order.setStatus(CafeOrderStatus.OPEN);
    order.setBusinessDate("2026-09-20");

    when(orderRepository.findByIdAndShopId("order-1", "shop-1")).thenReturn(Optional.of(order));
    when(orderRepository.save(any(CafeOrder.class))).thenAnswer(i -> i.getArgument(0));
    when(kotRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    when(punchRepository.save(any(CafeOrderPunch.class))).thenAnswer(i -> i.getArgument(0));
    when(punchRepository.countByShopIdAndOrderId("shop-1", "order-1")).thenReturn(0L);
    when(punchRepository.findByShopIdAndIdempotencyKey(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(sequenceService.allocate(anyString(), any(), eq(CafeSequenceSeries.KOT)))
        .thenReturn(41, 42);

    menuItem("m-biryani", "Biryani", "KITCHEN");
    menuItem("m-coke", "Coke", "BAR");
  }

  private void menuItem(String id, String name, String department) {
    MenuItem item = new MenuItem();
    item.setId(id);
    item.setName(name);
    item.setDepartment(department);
    item.setAvailable(true);
    when(menuLookup.findMenuItem("shop-1", id)).thenReturn(Optional.of(item));
  }

  private PunchCommand punch(String key, PunchLine... lines) {
    return PunchCommand.builder()
        .shopId("shop-1")
        .userId("user-1")
        .orderId("order-1")
        .idempotencyKey(key)
        .lines(List.of(lines))
        .build();
  }

  private PunchLine line(String ref, int qty, String note) {
    return PunchLine.builder().sellableRef(ref).quantity(qty).note(note).build();
  }

  @Test
  void punchSplitsLinesByDepartment() {
    List<KotView> kots =
        store.punch(
            punch("key-1", line("menu:m-biryani", 2, "extra hot"), line("menu:m-coke", 1, null)));

    assertEquals(2, kots.size());
    assertEquals(List.of("BAR", "KITCHEN"), kots.stream().map(KotView::getDepartment).sorted().toList());
    assertEquals(List.of(41, 42), kots.stream().map(KotView::getKotNo).sorted().toList());
  }

  @Test
  void punchInOneDepartmentYieldsOneTicket() {
    List<KotView> kots = store.punch(punch("key-2", line("menu:m-biryani", 1, null)));

    assertEquals(1, kots.size());
    assertEquals("KITCHEN", kots.get(0).getDepartment());
    assertEquals(1, kots.get(0).getRoundNo());
  }

  @Test
  void punchAppendsLinesToTheOrderCarryingTheNote() {
    store.punch(punch("key-2b", line("menu:m-biryani", 2, "  no onion ")));

    assertEquals(1, order.getLines().size());
    assertEquals("no onion", order.getLines().get(0).getNote());
    assertEquals("Biryani", order.getLines().get(0).getName());
    assertEquals(2, order.getLines().get(0).getQuantity());
  }

  @Test
  void nullMenuDepartmentFallsBackToKitchen() {
    MenuItem item = new MenuItem();
    item.setId("m-water");
    item.setName("Water");
    item.setDepartment(null);
    item.setAvailable(true);
    when(menuLookup.findMenuItem("shop-1", "m-water")).thenReturn(Optional.of(item));

    List<KotView> kots = store.punch(punch("key-3", line("menu:m-water", 1, null)));

    assertEquals("KITCHEN", kots.get(0).getDepartment());
  }

  @Test
  void replayOfACompletedPunchReturnsOriginalTicketsAndCreatesNothing() {
    CafeOrderPunch done = new CafeOrderPunch();
    done.setId("punch-1");
    done.setShopId("shop-1");
    done.setOrderId("order-1");
    done.setIdempotencyKey("key-replay");
    done.setRoundNo(1);
    done.setStatus(CafePunchStatus.COMPLETE);
    done.setKotIds(List.of("kot-1"));
    when(punchRepository.findByShopIdAndIdempotencyKey("shop-1", "key-replay"))
        .thenReturn(Optional.of(done));

    CafeKot existing = new CafeKot();
    existing.setId("kot-1");
    existing.setShopId("shop-1");
    existing.setOrderId("order-1");
    existing.setKotNo(41);
    existing.setDepartment("KITCHEN");
    existing.setRoundNo(1);
    existing.setStatus(CafeKotStatus.ISSUED);
    existing.setPunchId("punch-1");
    when(kotRepository.findByShopIdAndPunchId("shop-1", "punch-1")).thenReturn(List.of(existing));

    List<KotView> kots = store.punch(punch("key-replay", line("menu:m-biryani", 1, null)));

    assertEquals(1, kots.size());
    assertEquals("kot-1", kots.get(0).getKotId());
    verify(kotRepository, never()).saveAll(any());
    verify(sequenceService, never()).allocate(anyString(), any(), any());
  }

  @Test
  void replayOfAClaimedPunchDiscardsPartialTicketsAndRedrives() {
    CafeOrderPunch claimed = new CafeOrderPunch();
    claimed.setId("punch-2");
    claimed.setShopId("shop-1");
    claimed.setOrderId("order-1");
    claimed.setIdempotencyKey("key-crash");
    claimed.setRoundNo(1);
    claimed.setStatus(CafePunchStatus.CLAIMED);
    when(punchRepository.findByShopIdAndIdempotencyKey("shop-1", "key-crash"))
        .thenReturn(Optional.of(claimed));

    List<KotView> kots =
        store.punch(
            punch("key-crash", line("menu:m-biryani", 1, null), line("menu:m-coke", 1, null)));

    verify(kotRepository).deleteByShopIdAndPunchId("shop-1", "punch-2");
    assertEquals(2, kots.size());
  }

  @Test
  void redriveKeepsTheOriginalRoundNumber() {
    CafeOrderPunch claimed = new CafeOrderPunch();
    claimed.setId("punch-5");
    claimed.setShopId("shop-1");
    claimed.setOrderId("order-1");
    claimed.setIdempotencyKey("key-round");
    claimed.setRoundNo(3);
    claimed.setStatus(CafePunchStatus.CLAIMED);
    when(punchRepository.findByShopIdAndIdempotencyKey("shop-1", "key-round"))
        .thenReturn(Optional.of(claimed));

    List<KotView> kots = store.punch(punch("key-round", line("menu:m-biryani", 1, null)));

    assertEquals(3, kots.get(0).getRoundNo());
  }

  @Test
  void claimIsWrittenBeforeAnyTicket() {
    InOrder inOrder = inOrder(punchRepository, kotRepository);

    store.punch(punch("key-order", line("menu:m-biryani", 1, null)));

    inOrder.verify(punchRepository).save(any(CafeOrderPunch.class));
    inOrder.verify(kotRepository).saveAll(any());
  }

  @Test
  void aConcurrentClaimLosesAndReturnsTheWinnersTickets() {
    when(punchRepository.save(any(CafeOrderPunch.class)))
        .thenThrow(new DuplicateKeyException("duplicate"));

    CafeOrderPunch winner = new CafeOrderPunch();
    winner.setId("punch-3");
    winner.setShopId("shop-1");
    winner.setStatus(CafePunchStatus.COMPLETE);
    winner.setKotIds(List.of("kot-9"));
    when(punchRepository.findByShopIdAndIdempotencyKey("shop-1", "key-race"))
        .thenReturn(Optional.empty(), Optional.of(winner));

    CafeKot kot = new CafeKot();
    kot.setId("kot-9");
    kot.setShopId("shop-1");
    kot.setStatus(CafeKotStatus.ISSUED);
    when(kotRepository.findByShopIdAndPunchId("shop-1", "punch-3")).thenReturn(List.of(kot));

    List<KotView> kots = store.punch(punch("key-race", line("menu:m-biryani", 1, null)));

    assertEquals(List.of("kot-9"), kots.stream().map(KotView::getKotId).toList());
    verify(kotRepository, never()).saveAll(any());
  }

  @Test
  void blankIdempotencyKeyIsRejected() {
    assertThrows(
        ValidationException.class, () -> store.punch(punch("  ", line("menu:m-biryani", 1, null))));
  }

  @Test
  void punchOnBilledOrderIsRejected() {
    order.setStatus(CafeOrderStatus.BILLED);
    assertThrows(
        ValidationException.class, () -> store.punch(punch("key-4", line("menu:m-biryani", 1, null))));
  }

  @Test
  void malformedSellableRefIsAValidationErrorNotACrash() {
    assertThrows(
        ValidationException.class, () -> store.punch(punch("key-5", line("not-a-ref", 1, null))));
  }

  @Test
  void unavailableMenuItemIsRejected() {
    MenuItem item = new MenuItem();
    item.setId("m-off");
    item.setName("Sold Out");
    item.setAvailable(false);
    when(menuLookup.findMenuItem("shop-1", "m-off")).thenReturn(Optional.of(item));

    assertThrows(
        ValidationException.class, () -> store.punch(punch("key-6", line("menu:m-off", 1, null))));
  }

  @Test
  void anOrderOpenAcrossMidnightKeepsItsOwnBusinessDate() {
    order.setBusinessDate("2026-09-20");

    store.punch(punch("key-7", line("menu:m-biryani", 1, null)));

    verify(sequenceService)
        .allocate("shop-1", LocalDate.of(2026, 9, 20), CafeSequenceSeries.KOT);
  }
}
