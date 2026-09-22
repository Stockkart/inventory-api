package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.kot.CafeKotPort;
import com.inventory.pluginengine.kot.CafeKotTab;
import com.inventory.pluginengine.kot.CafeKotTicket;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.repository.PurchaseRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CafeKotTabServiceTest {

  private CafeKotPort port;
  private PurchaseRepository purchaseRepository;
  private CafeKotTabService service;

  @BeforeEach
  void setUp() {
    port = mock(CafeKotPort.class);
    purchaseRepository = mock(PurchaseRepository.class);

    PluginRegistry registry = mock(PluginRegistry.class);
    VerticalPlugin plugin = mock(VerticalPlugin.class);
    when(plugin.getCafeKotPort()).thenReturn(Optional.of(port));
    when(registry.require("cafe")).thenReturn(plugin);

    service = new CafeKotTabService(registry, purchaseRepository);
  }

  @Test
  void flushReturnsItsTickets() {
    CafeKotTicket ticket = CafeKotTicket.builder().kotId("k1").shopId("s1").build();
    when(port.flush("s1", "u1", "tab1", null, "idem-1")).thenReturn(List.of(ticket));

    List<CafeKotTicket> result = service.flush("s1", "u1", "tab1", null, "idem-1");

    assertEquals(List.of(ticket), result);
  }

  @Test
  void listDelegatesToThePort() {
    CafeKotTab tab = CafeKotTab.builder().id("t1").tokenNo("1").status("OPEN").build();
    when(port.listTabs("s1", "u1")).thenReturn(List.of(tab));

    assertEquals(List.of(tab), service.list("s1", "u1"));
  }

  @Test
  void anotherCashiersTabIsNotFound() {
    // The port owns this scoping (findByIdAndShopIdAndUserId); the service just relays whatever
    // it throws, so a mismatched userId resolves the same as an absent tab.
    when(port.removeTabLine("s1", "u1", "someone-elses-tab", "line1"))
        .thenThrow(new ResourceNotFoundException("CafeTab", "tabId", "someone-elses-tab"));

    assertThrows(
        ResourceNotFoundException.class,
        () -> service.removeLine("s1", "u1", "someone-elses-tab", "line1"));
  }

  @Test
  void flushToAnotherCashiersBillIsRejectedBeforeThePortIsTouched() {
    Purchase purchase = new Purchase();
    purchase.setId("bill1");
    purchase.setShopId("s1");
    purchase.setUserId("someone-else");
    when(purchaseRepository.findById("bill1")).thenReturn(Optional.of(purchase));

    assertThrows(
        ValidationException.class, () -> service.flush("s1", "u1", "tab1", "bill1", "idem-1"));
    verify(port, never()).flush(any(), any(), any(), any(), any());
  }

  @Test
  void flushToAnotherShopsBillIsRejectedBeforeThePortIsTouched() {
    Purchase purchase = new Purchase();
    purchase.setId("bill1");
    purchase.setShopId("other-shop");
    purchase.setUserId("u1");
    when(purchaseRepository.findById("bill1")).thenReturn(Optional.of(purchase));

    assertThrows(
        ValidationException.class, () -> service.flush("s1", "u1", "tab1", "bill1", "idem-1"));
    verify(port, never()).flush(any(), any(), any(), any(), any());
  }

  @Test
  void flushToAnUnknownBillIsResourceNotFound() {
    when(purchaseRepository.findById("missing")).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () -> service.flush("s1", "u1", "tab1", "missing", "idem-1"));
    verify(port, never()).flush(any(), any(), any(), any(), any());
  }

  @Test
  void flushToAnOwnedBillReachesThePort() {
    Purchase purchase = new Purchase();
    purchase.setId("bill1");
    purchase.setShopId("s1");
    purchase.setUserId("u1");
    when(purchaseRepository.findById("bill1")).thenReturn(Optional.of(purchase));
    when(port.flush("s1", "u1", "tab1", "bill1", "idem-1")).thenReturn(List.of());

    service.flush("s1", "u1", "tab1", "bill1", "idem-1");

    verify(port).flush("s1", "u1", "tab1", "bill1", "idem-1");
  }

  @Test
  void flushToANewBillNeverConsultsThePurchaseRepository() {
    when(port.flush(eq("s1"), eq("u1"), eq("tab1"), eq(null), eq("idem-1")))
        .thenReturn(List.of());

    service.flush("s1", "u1", "tab1", null, "idem-1");

    verify(purchaseRepository, never()).findById(any());
  }
}
