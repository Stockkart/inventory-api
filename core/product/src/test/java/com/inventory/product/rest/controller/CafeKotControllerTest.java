package com.inventory.product.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.service.vertical.CafeKotService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the tenant guarantee the document endpoint depends on structurally (no {@code
 * @RequestBody} carrying a shopId) but does not otherwise verify: shopId is read only from the
 * request attribute the auth interceptor sets, never anything the caller sent.
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
  void kotDocumentUsesShopIdFromRequestAttributes() {
    byte[] pdf = {1, 2, 3};
    when(cafeKotService.kotDocument("shop-1", "k1")).thenReturn(pdf);

    var response = controller.kotDocument("k1", httpRequest);

    assertEquals(pdf.length, response.getBody().length);
    verify(cafeKotService).kotDocument("shop-1", "k1");
  }
}
