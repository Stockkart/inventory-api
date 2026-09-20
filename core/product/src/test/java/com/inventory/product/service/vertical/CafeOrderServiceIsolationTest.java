package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.documentservice.service.KotPdfService;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.order.RunningOrderStore;
import com.inventory.product.service.CheckoutService;
import com.inventory.user.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every id-addressed operation must fail for a shop that does not own the entity.
 *
 * <p>Ids travel through REST, so this gets a test per endpoint rather than one representative
 * case: a single method that forgot to pass shopId through would be invisible otherwise.
 */
class CafeOrderServiceIsolationTest {

  private RunningOrderStore store;
  private KotPdfService kotPdfService;
  private RbacService rbacService;
  private CafeOrderService service;
  private HttpServletRequest httpRequest;

  @BeforeEach
  void setUp() {
    store = mock(RunningOrderStore.class);
    kotPdfService = mock(KotPdfService.class);
    rbacService = mock(RbacService.class);
    httpRequest = mock(HttpServletRequest.class);

    PluginRegistry registry = mock(PluginRegistry.class);
    VerticalPlugin plugin = mock(VerticalPlugin.class);
    when(plugin.getRunningOrderStore()).thenReturn(Optional.of(store));
    when(registry.require("cafe")).thenReturn(plugin);

    service =
        new CafeOrderService(registry, mock(CheckoutService.class), kotPdfService, rbacService);

    // shop-B owns nothing.
    when(store.findOrder("shop-B", "order-1")).thenReturn(Optional.empty());
    when(store.findKot("shop-B", "kot-1")).thenReturn(Optional.empty());
  }

  @Test
  void getOrderFromAnotherShopIsNotFound() {
    assertThrows(ResourceNotFoundException.class, () -> service.getOrder("shop-B", "order-1"));
  }

  @Test
  void kotDocumentFromAnotherShopIsNotFoundAndRendersNothing() {
    assertThrows(
        ResourceNotFoundException.class, () -> service.kotDocument("shop-B", "kot-1"));
    verify(kotPdfService, never()).generateKotPdf(any());
  }

  @Test
  void settleFromAnotherShopIsNotFound() {
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.settle("shop-B", "user-1", "order-1", "RESTAURANT", "CASH", httpRequest));
  }

  @Test
  void punchIsScopedToTheCallersShop() {
    service.punch("shop-B", "user-1", "order-1", "key-1", List.of());

    verify(store)
        .punch(
            org.mockito.ArgumentMatchers.argThat(
                command -> "shop-B".equals(command.getShopId())));
  }

  @Test
  void reprintIsScopedToTheCallersShop() {
    when(store.markReprinted("shop-B", "kot-1"))
        .thenThrow(new ResourceNotFoundException("CafeKot", "id", "kot-1"));

    assertThrows(ResourceNotFoundException.class, () -> service.reprint("shop-B", "kot-1"));
    verify(kotPdfService, never()).generateKotPdf(any());
  }

  @Test
  void voidChecksRbacAgainstTheCallersShopBeforeTouchingTheStore() {
    service.voidLines("shop-B", "user-1", "kot-1", List.of("l1"), "reason");

    verify(rbacService).requireModule("user-1", "shop-B", RbacService.MODULE_KOT_VOID);
    verify(store).voidLines("shop-B", "user-1", "kot-1", List.of("l1"), "reason");
  }

  @Test
  void cancelChecksRbacAgainstTheCallersShopBeforeTouchingTheStore() {
    service.cancel("shop-B", "user-1", "order-1", "reason");

    verify(rbacService).requireModule("user-1", "shop-B", RbacService.MODULE_KOT_VOID);
    verify(store).cancelOrder("shop-B", "user-1", "order-1", "reason");
  }

  @Test
  void listOpenOrdersIsScopedToTheCallersShop() {
    service.listOpenOrders("shop-B");

    verify(store).listOpenOrders("shop-B");
  }
}
