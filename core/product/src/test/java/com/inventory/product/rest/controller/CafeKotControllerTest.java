package com.inventory.product.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.service.vertical.CafeKotService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the tenant guarantee the punch endpoint depends on structurally (no {@code @RequestBody}
 * carrying a shopId) but does not otherwise verify: shopId and userId are read only from the
 * request attributes the auth interceptor sets, never anything the caller sent. A future body
 * field named shopId would break this with a green suite unless something here calls out what the
 * controller actually passes to the service.
 */
class CafeKotControllerTest {

  private CafeKotService cafeKotService;
  private CafeKotController controller;
  private HttpServletRequest httpRequest;

  @BeforeEach
  void setUp() {
    cafeKotService = mock(CafeKotService.class);
    controller = new CafeKotController(cafeKotService);
    httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getAttribute("shopId")).thenReturn("shop-1");
    when(httpRequest.getAttribute("userId")).thenReturn("user-1");
  }

  @Test
  void punchUsesShopIdAndUserIdFromRequestAttributes() {
    when(cafeKotService.punch("shop-1", "user-1", "p1", "idem-1")).thenReturn(List.of());

    controller.punch("p1", "idem-1", httpRequest);

    verify(cafeKotService).punch("shop-1", "user-1", "p1", "idem-1");
  }

  @Test
  void punchWithNoIdempotencyKeyHeaderStillReachesTheServiceGuard() {
    // required = false on the header means a missing Idempotency-Key arrives as null, not a
    // request that never reaches the service — the same guard as a blank header value.
    controller.punch("p1", null, httpRequest);

    verify(cafeKotService).punch("shop-1", "user-1", "p1", null);
  }

  @Test
  void kotDocumentUsesShopIdFromRequestAttributes() {
    byte[] pdf = {1, 2, 3};
    when(cafeKotService.kotDocument("shop-1", "k1")).thenReturn(pdf);

    var response = controller.kotDocument("k1", httpRequest);

    assertEquals(pdf.length, response.getBody().length);
    verify(cafeKotService).kotDocument("shop-1", "k1");
  }
}
