package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.pluginengine.kot.CafeKotTicket;
import com.inventory.plugins.cafe.domain.CafeKot;
import com.inventory.plugins.cafe.domain.CafeKotKind;
import com.inventory.plugins.cafe.domain.CafeKotLine;
import com.inventory.plugins.cafe.domain.CafeKotRepository;
import com.inventory.plugins.cafe.domain.CafeKotStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The adapter is the only bridge {@code core/product} has into the cafe punch flow, so what it
 * maps onto {@link CafeKotTicket} — and which repository query it uses to read one back — is the
 * whole tenant boundary and the whole ticket-content contract from core's point of view.
 *
 * <p>Recovered from the retired {@code CafeKotPunchAdapterTest}; the VOIDED-status case is gone
 * with the status itself, and reprint is new since.
 */
class CafeKotAdapterTest {

  private CafeKotRepository kotRepository;
  private CafeKotPunchService punchService;
  private CafeKotAdapter adapter;

  @BeforeEach
  void setUp() {
    kotRepository = mock(CafeKotRepository.class);
    punchService = mock(CafeKotPunchService.class);
    adapter = new CafeKotAdapter(kotRepository, punchService);
  }

  private static CafeKot kot() {
    CafeKot kot = new CafeKot();
    kot.setId("k1");
    kot.setShopId("shop-1");
    kot.setPurchaseId("p1");
    kot.setKotNo(41);
    kot.setDepartment("KITCHEN");
    kot.setRoundNo(1);
    kot.setKind(CafeKotKind.ISSUE);
    kot.setStatus(CafeKotStatus.ISSUED);
    kot.setTableLabel("T4");
    kot.setTokenNo("12");
    kot.setBusinessDate("2026-09-21");
    CafeKotLine line = new CafeKotLine();
    line.setLineId("menu:m1");
    line.setName("Tea");
    line.setQuantity(2);
    kot.setLines(List.of(line));
    return kot;
  }

  @Test
  void punchDelegatesAndMapsEveryCreatedTicket() {
    when(punchService.punch("shop-1", "user-1", "p1", "idem-1")).thenReturn(List.of(kot()));

    List<CafeKotTicket> tickets = adapter.punch("shop-1", "user-1", "p1", "idem-1");

    assertEquals(1, tickets.size());
    assertEquals("k1", tickets.get(0).getKotId());
    assertEquals("ISSUE", tickets.get(0).getKind());
    verify(punchService).punch("shop-1", "user-1", "p1", "idem-1");
  }

  @Test
  void findKotIsScopedByShopIdThroughTheRepositoryQuery() {
    when(kotRepository.findByIdAndShopId("k1", "shop-1")).thenReturn(Optional.of(kot()));

    Optional<CafeKotTicket> result = adapter.findKot("shop-1", "k1");

    assertTrue(result.isPresent());
    // The scoping guarantee lives entirely in which repository method is called with which
    // arguments — findByIdAndShopId, not findById — so pinning the call is pinning the guarantee.
    verify(kotRepository).findByIdAndShopId("k1", "shop-1");
  }

  @Test
  void aTicketForAnotherShopResolvesToNothing() {
    when(kotRepository.findByIdAndShopId("k1", "other-shop")).thenReturn(Optional.empty());

    assertTrue(adapter.findKot("other-shop", "k1").isEmpty());
    verify(kotRepository).findByIdAndShopId("k1", "other-shop");
  }

  @Test
  void findKotCarriesTableTokenAndStatusOntoTheTicket() {
    when(kotRepository.findByIdAndShopId("k1", "shop-1")).thenReturn(Optional.of(kot()));

    CafeKotTicket ticket = adapter.findKot("shop-1", "k1").orElseThrow();

    assertEquals("T4", ticket.getTableLabel());
    assertEquals("12", ticket.getTokenNo());
    assertEquals("ISSUED", ticket.getStatus());
    assertEquals("ISSUE", ticket.getKind());
    assertEquals(1, ticket.getLines().size());
    assertEquals("Tea", ticket.getLines().get(0).getName());
  }

  @Test
  void aCancelTicketFromAPunchIsAdaptedLikeAnyOther() {
    // There is no cancel call on the port any more: a CANCEL slip is a ticket the punch produced
    // from a negative delta, and it reaches core through punch() like every other ticket.
    CafeKot cancel = kot();
    cancel.setKind(CafeKotKind.CANCEL);
    when(punchService.punch("shop-1", "user-1", "p1", "idem-1")).thenReturn(List.of(cancel));

    List<CafeKotTicket> tickets = adapter.punch("shop-1", "user-1", "p1", "idem-1");

    assertEquals(List.of("CANCEL"), tickets.stream().map(CafeKotTicket::getKind).toList());
  }

  @Test
  void reprintBumpsTheCountAndCreatesNoNewTicket() {
    CafeKot stored = kot();
    stored.setReprintCount(1);
    when(kotRepository.findByIdAndShopId("k1", "shop-1")).thenReturn(Optional.of(stored));
    when(kotRepository.save(stored)).thenReturn(stored);

    CafeKotTicket ticket = adapter.reprint("shop-1", "k1", "idem-1");

    assertEquals("k1", ticket.getKotId(), "the same ticket, never a new one");
    assertEquals(2, ticket.getReprintCount());
    verify(kotRepository).save(stored);
  }
}
