package com.inventory.product.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
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

  @Test
  void reprintUsesShopIdFromRequestAttributes() {
    byte[] pdf = {4, 5, 6};
    when(cafeKotService.reprint("shop-1", "k1", "idem-1")).thenReturn(pdf);

    var response = controller.reprint("k1", "idem-1", httpRequest);

    assertEquals(pdf.length, response.getBody().length);
    verify(cafeKotService).reprint("shop-1", "k1", "idem-1");
  }

  @Test
  void reprintRejectsABlankIdempotencyKeyBeforeTouchingTheService() {
    assertThrows(ValidationException.class, () -> controller.reprint("k1", "  ", httpRequest));
    verify(cafeKotService, never()).reprint(any(), any(), any());
  }

  @Test
  void reprintRejectsAMissingIdempotencyKeyBeforeTouchingTheService() {
    assertThrows(ValidationException.class, () -> controller.reprint("k1", null, httpRequest));
    verify(cafeKotService, never()).reprint(any(), any(), any());
  }
}
