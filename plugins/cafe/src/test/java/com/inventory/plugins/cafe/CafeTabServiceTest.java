package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.inventory.plugins.cafe.domain.CafeTabRepository;
import com.inventory.plugins.cafe.domain.CafeTabStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mirrors {@code QuotationService}'s open-quotation rules exactly: scoped to shop and user, a
 * hard cap that refuses rather than evicts, no expiry/TTL/rollover, and closes only explicitly.
 */
class CafeTabServiceTest {

  private static final String SHOP_ID = "shop-1";
  private static final String USER_ID = "user-1";
  private static final String OTHER_USER_ID = "user-2";

  private CafeTabRepository cafeTabRepository;
  private CafeTokenService cafeTokenService;
  private ShopMenuLookup shopMenuLookup;
  private CafeTabService service;

  @BeforeEach
  void setUp() {
    cafeTabRepository = mock(CafeTabRepository.class);
    cafeTokenService = mock(CafeTokenService.class);
    shopMenuLookup = mock(ShopMenuLookup.class);
    service = new CafeTabService(cafeTabRepository, cafeTokenService, shopMenuLookup);

    when(cafeTabRepository.save(any(CafeTab.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void aTabIsScopedToTheCashierWhoOpenedIt() {
    when(cafeTabRepository.findByShopIdAndUserIdAndStatusOrderByCreatedAtDesc(
            SHOP_ID, OTHER_USER_ID, CafeTabStatus.OPEN))
        .thenReturn(List.of());
    assertTrue(service.list(SHOP_ID, OTHER_USER_ID).isEmpty());

    // Another user's id cannot load the tab by id either.
    when(cafeTabRepository.findByIdAndShopIdAndUserId("tab-1", SHOP_ID, OTHER_USER_ID))
        .thenReturn(Optional.empty());
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.addLine(SHOP_ID, OTHER_USER_ID, "tab-1", "menu:item-1", 1, null));
  }

  @Test
  void theThirtyFirstOpenTabIsRefusedRatherThanEvictingTheOldest() {
    when(cafeTabRepository.countByShopIdAndUserIdAndStatus(SHOP_ID, USER_ID, CafeTabStatus.OPEN))
        .thenReturn(30L);

    assertThrows(ValidationException.class, () -> service.open(SHOP_ID, USER_ID));

    verify(cafeTabRepository, never()).save(any(CafeTab.class));
    verify(cafeTabRepository, never()).delete(any(CafeTab.class));
    verify(cafeTabRepository, never()).deleteById(anyString());
  }

  @Test
  void aTabOpenedYesterdayIsStillOpenTodayWithItsOriginalToken() {
    CafeTab tab = new CafeTab();
    tab.setId("tab-old");
    tab.setShopId(SHOP_ID);
    tab.setUserId(USER_ID);
    tab.setTokenNo("7");
    tab.setStatus(CafeTabStatus.OPEN);
    tab.setLines(new ArrayList<>());
    tab.setCreatedAt(Instant.now().minusSeconds(60L * 60 * 30));
    tab.setUpdatedAt(Instant.now().minusSeconds(60L * 60 * 30));

    when(cafeTabRepository.findByShopIdAndUserIdAndStatusOrderByCreatedAtDesc(
            SHOP_ID, USER_ID, CafeTabStatus.OPEN))
        .thenReturn(List.of(tab));

    List<CafeTab> open = service.list(SHOP_ID, USER_ID);

    assertEquals(1, open.size());
    assertEquals(CafeTabStatus.OPEN, open.get(0).getStatus());
    assertEquals("7", open.get(0).getTokenNo());
    verify(cafeTabRepository, never()).save(any(CafeTab.class));
    verify(cafeTokenService, never()).allocateToken(anyString(), anyString());
  }

  @Test
  void closingATabIsTheOnlyWayItLeavesTheOpenState() {
    CafeTab tab = new CafeTab();
    tab.setId("tab-1");
    tab.setShopId(SHOP_ID);
    tab.setUserId(USER_ID);
    tab.setStatus(CafeTabStatus.OPEN);
    tab.setLines(new ArrayList<>());

    when(cafeTabRepository.findByIdAndShopIdAndUserId("tab-1", SHOP_ID, USER_ID))
        .thenReturn(Optional.of(tab));

    service.close(SHOP_ID, USER_ID, "tab-1");

    assertEquals(CafeTabStatus.CLOSED, tab.getStatus());
    verify(cafeTabRepository).save(tab);

    // Once closed, it can no longer be mutated as an open tab.
    assertThrows(
        ValidationException.class,
        () -> service.addLine(SHOP_ID, USER_ID, "tab-1", "menu:item-1", 1, null));
  }

  @Test
  void everyTabReadIsScopedByShopAndUser() {
    when(cafeTabRepository.findByShopIdAndUserIdAndStatusOrderByCreatedAtDesc(
            eq(SHOP_ID), eq(USER_ID), eq(CafeTabStatus.OPEN)))
        .thenReturn(List.of());

    service.list(SHOP_ID, USER_ID);

    verify(cafeTabRepository)
        .findByShopIdAndUserIdAndStatusOrderByCreatedAtDesc(SHOP_ID, USER_ID, CafeTabStatus.OPEN);

    when(cafeTabRepository.findByIdAndShopIdAndUserId("tab-2", SHOP_ID, USER_ID))
        .thenReturn(Optional.empty());
    assertThrows(
        ResourceNotFoundException.class, () -> service.close(SHOP_ID, USER_ID, "tab-2"));
    verify(cafeTabRepository).findByIdAndShopIdAndUserId("tab-2", SHOP_ID, USER_ID);
  }

  @Test
  void aComposedLineFreezesThePriceTheCustomerWasQuoted() {
    CafeTab tab = new CafeTab();
    tab.setId("tab-1");
    tab.setShopId(SHOP_ID);
    tab.setUserId(USER_ID);
    tab.setStatus(CafeTabStatus.OPEN);
    tab.setLines(new ArrayList<>());
    when(cafeTabRepository.findByIdAndShopIdAndUserId("tab-1", SHOP_ID, USER_ID))
        .thenReturn(Optional.of(tab));

    MenuItem tea = new MenuItem();
    tea.setId("tea");
    tea.setName("Tea");
    tea.setSellingPrice(new BigDecimal("30.00"));
    tea.setCgst("2.5");
    tea.setSgst("2.5");
    tea.setDepartment("kitchen");
    when(shopMenuLookup.findMenuItem(SHOP_ID, "tea")).thenReturn(Optional.of(tea));

    service.addLine(SHOP_ID, USER_ID, "tab-1", "menu:tea", 2, null);

    CafeTabLine line = tab.getLines().get(0);
    assertEquals(new BigDecimal("30.00"), line.getPrice(), "what the customer was quoted");
    assertEquals("2.5", line.getCgst());
    assertEquals("2.5", line.getSgst());
    assertEquals("KITCHEN", line.getDepartment(), "and the station, as before");

    // The menu moves on mid-round.
    tea.setSellingPrice(new BigDecimal("50.00"));
    assertEquals(
        new BigDecimal("30.00"),
        tab.getLines().get(0).getPrice(),
        "the composed line is not re-priced under the customer");

    service.updateLine(SHOP_ID, USER_ID, "tab-1", line.getLineRef(), 3, null);
    assertEquals(new BigDecimal("30.00"), line.getPrice(), "nor by editing the quantity");
    assertEquals(3, line.getQuantity());
  }

  @Test
  void openAllocatesATokenUnderTheTabScopeDistinctFromTheBillScope() {
    when(cafeTabRepository.countByShopIdAndUserIdAndStatus(SHOP_ID, USER_ID, CafeTabStatus.OPEN))
        .thenReturn(0L);
    when(cafeTokenService.allocateToken(SHOP_ID, "TAB")).thenReturn("1");

    CafeTab tab = service.open(SHOP_ID, USER_ID);

    assertEquals("1", tab.getTokenNo());
    assertEquals(CafeTabStatus.OPEN, tab.getStatus());
    assertTrue(tab.getLines().isEmpty());
    verify(cafeTokenService).allocateToken(SHOP_ID, "TAB");
    verify(cafeTokenService, never()).allocateToken(SHOP_ID, CafeTokenService.SCOPE_BILL);
  }
}
