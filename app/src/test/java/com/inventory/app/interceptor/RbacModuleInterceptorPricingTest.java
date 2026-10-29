package com.inventory.app.interceptor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.user.service.RbacService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Pricing writes (PATCH /pricing/{id}, POST /pricing/bulk-update) need the product-search grant
 * for rates and the default rate, the same check PUT /inventory/{id} applies; reads stay open.
 */
@ExtendWith(MockitoExtension.class)
class RbacModuleInterceptorPricingTest {

  @Mock private RbacService rbacService;
  @InjectMocks private RbacModuleInterceptor interceptor;

  private static MockHttpServletRequest request(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setAttribute("userId", "u1");
    request.setAttribute("shopId", "s1");
    return request;
  }

  @Test
  void patchingAPricingRecordNeedsTheRatesGrant() {
    assertTrue(interceptor.preHandle(
        request("PATCH", "/api/v1/pricing/p1"), new MockHttpServletResponse(), null));

    verify(rbacService).validateProductSearchFieldUpdates(
        "u1", "s1", RbacModuleInterceptor.PRICING_WRITE_FIELDS);
  }

  @Test
  void bulkUpdatingPricingNeedsTheRatesGrant() {
    interceptor.preHandle(
        request("POST", "/api/v1/pricing/bulk-update"), new MockHttpServletResponse(), null);

    verify(rbacService).validateProductSearchFieldUpdates(
        "u1", "s1", RbacModuleInterceptor.PRICING_WRITE_FIELDS);
  }

  @Test
  void aMemberWithoutTheGrantIsRefused() {
    doThrow(new BaseException(ErrorCode.ACCESS_DENIED, "You do not have permission to edit products"))
        .when(rbacService)
        .validateProductSearchFieldUpdates("u1", "s1", RbacModuleInterceptor.PRICING_WRITE_FIELDS);

    assertThrows(BaseException.class, () -> interceptor.preHandle(
        request("PATCH", "/api/v1/pricing/p1"), new MockHttpServletResponse(), null));
  }

  @Test
  void readingPricingIsNotChecked() {
    assertTrue(interceptor.preHandle(
        request("GET", "/api/v1/pricing/p1"), new MockHttpServletResponse(), null));

    verifyNoInteractions(rbacService);
  }

  @Test
  void postingToAPricingIdIsNotTreatedAsABulkUpdate() {
    interceptor.preHandle(request("POST", "/api/v1/pricing/p1"), new MockHttpServletResponse(), null);

    verifyNoInteractions(rbacService);
  }
}
