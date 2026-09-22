package com.inventory.product.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.kot.CafeKotTab;
import com.inventory.product.rest.dto.request.CafeFlushRequest;
import com.inventory.product.rest.dto.request.CafeTabLineRequest;
import com.inventory.product.service.vertical.CafeKotTabService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the tenant guarantee every tab endpoint depends on structurally: shopId and userId are
 * read only from request attributes the auth interceptor sets, never from the body, and the
 * flush endpoint refuses to touch the service at all without a non-blank Idempotency-Key.
 */
class CafeTabControllerTest {

  private CafeKotTabService cafeTabService;
  private CafeTabController controller;
  private HttpServletRequest httpRequest;

  @BeforeEach
  void setUp() {
    cafeTabService = mock(CafeKotTabService.class);
    controller = new CafeTabController(cafeTabService);
    httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getAttribute("shopId")).thenReturn("shop-1");
    when(httpRequest.getAttribute("userId")).thenReturn("user-1");
  }

  @Test
  void listUsesShopAndUserIdFromRequestAttributes() {
    when(cafeTabService.list("shop-1", "user-1")).thenReturn(List.of());

    controller.list(httpRequest);

    verify(cafeTabService).list("shop-1", "user-1");
  }

  @Test
  void openUsesShopAndUserIdFromRequestAttributes() {
    CafeKotTab tab = CafeKotTab.builder().id("t1").tokenNo("1").status("OPEN").build();
    when(cafeTabService.open("shop-1", "user-1")).thenReturn(tab);

    var response = controller.open(httpRequest);

    assertEquals(tab, response.getBody().getData());
    verify(cafeTabService).open("shop-1", "user-1");
  }

  @Test
  void addOrUpdateLineUsesShopAndUserIdFromRequestAttributes() {
    CafeTabLineRequest request = new CafeTabLineRequest();
    request.setSellableRef("menu:tea");
    request.setQuantity(2);
    CafeKotTab tab = CafeKotTab.builder().id("t1").build();
    when(cafeTabService.addOrUpdateLine("shop-1", "user-1", "t1", null, "menu:tea", 2, null))
        .thenReturn(tab);

    controller.addOrUpdateLine("t1", request, httpRequest);

    verify(cafeTabService)
        .addOrUpdateLine("shop-1", "user-1", "t1", null, "menu:tea", 2, null);
  }

  @Test
  void removeLineUsesShopAndUserIdFromRequestAttributes() {
    controller.removeLine("t1", "line1", httpRequest);

    verify(cafeTabService).removeLine("shop-1", "user-1", "t1", "line1");
  }

  @Test
  void closeUsesShopAndUserIdFromRequestAttributes() {
    controller.close("t1", httpRequest);

    verify(cafeTabService).close("shop-1", "user-1", "t1");
  }

  @Test
  void flushUsesShopAndUserIdFromRequestAttributesAndPassesTheHeader() {
    CafeFlushRequest request = new CafeFlushRequest();
    request.setPurchaseId("bill1");
    when(cafeTabService.flush("shop-1", "user-1", "t1", "bill1", "idem-1"))
        .thenReturn(List.of());

    controller.flush("t1", request, "idem-1", httpRequest);

    verify(cafeTabService).flush("shop-1", "user-1", "t1", "bill1", "idem-1");
  }

  @Test
  void flushRejectsABlankIdempotencyKeyBeforeTouchingTheService() {
    CafeFlushRequest request = new CafeFlushRequest();

    assertThrows(
        ValidationException.class, () -> controller.flush("t1", request, "   ", httpRequest));
    verify(cafeTabService, never()).flush(any(), any(), any(), any(), any());
  }

  @Test
  void flushRejectsAMissingIdempotencyKeyBeforeTouchingTheService() {
    CafeFlushRequest request = new CafeFlushRequest();

    assertThrows(
        ValidationException.class, () -> controller.flush("t1", request, null, httpRequest));
    verify(cafeTabService, never()).flush(any(), any(), any(), any(), any());
  }
}
