package com.inventory.app.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.user.rest.dto.response.ShopAccessResponse;
import com.inventory.user.service.RbacService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** HR rules of the RBAC interceptor: /hr/** needs module hr, /hr/me/** is self-service. */
@ExtendWith(MockitoExtension.class)
class RbacModuleInterceptorTest {

  @Mock private RbacService rbacService;
  @InjectMocks private RbacModuleInterceptor interceptor;

  private static MockHttpServletRequest request(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setAttribute("userId", "u1");
    request.setAttribute("shopId", "s1");
    return request;
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "/api/v1/hr/me", "/api/v1/hr/me/attendance", "/api/v1/hr/me/payslips/l1/pdf",
      "/api/v1/hr/me/notifications/read-all"})
  void selfServicePathsSkipTheModuleCheck(String path) {
    assertTrue(interceptor.preHandle(request(path), new MockHttpServletResponse(), null));
    verifyNoInteractions(rbacService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/hr", "/api/v1/hr/employees", "/api/v1/hr/settings",
      "/api/v1/hr/members", "/api/v1/hr/mex", "/api/v1/hr;x=1/employees",
      "/api/v1/hr/employees;jsessionid=abc", "/api//v1/hr/employees"})
  void otherHrPathsNeedTheHrModule(String path) {
    ShopAccessResponse access = new ShopAccessResponse();
    when(rbacService.requireModule("u1", "s1", RbacService.MODULE_HR)).thenReturn(access);
    MockHttpServletRequest request = request(path);

    assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));

    verify(rbacService).requireModule("u1", "s1", RbacService.MODULE_HR);
  }

  /** Spring routes "/hr/me;x=1" to the /hr/me handler (matrix params dropped), so it skips too. */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/hr/me;x=1", "/api/v1/hr/me;jsessionid=abc/attendance",
      "/api/v1/hr/%6De/attendance"})
  void decodedSelfServicePathsSkipLikeSpringRoutesThem(String path) {
    assertTrue(interceptor.preHandle(request(path), new MockHttpServletResponse(), null));
    verifyNoInteractions(rbacService);
  }

  /** Paths Spring would route to accounting although the raw URI hides the prefix. */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/accounting;jsessionid=1/journals",
      "/api/v1/%61ccounting/journals", "/api//v1/accounting/journals",
      "/api/v1/accounting;a=b"})
  void accountingRuleMatchesTheDecodedPath(String path) {
    when(rbacService.requireModule("u1", "s1", RbacService.MODULE_ACCOUNTING))
        .thenReturn(new ShopAccessResponse());

    assertTrue(interceptor.preHandle(request(path), new MockHttpServletResponse(), null));

    verify(rbacService).requireModule("u1", "s1", RbacService.MODULE_ACCOUNTING);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "/api/v1/shops/me/access/../../accounting/x",
      "/api/v1/shops/me/access/%2e%2e/%2E%2E/accounting/x",
      "/api/v1/hr/me/../employees",
      "/api/v1/hr/me/%2e%2e/employees",
      "/api/v1/hr/me/..;x=1/employees",
      "/api/v1/hr/me;jsessionid=1/../employees",
      "/api/v1/hr/me/./attendance",
      "/api/v1/hr/me%2F..%2Femployees",
      "/api/v1/hr%2fme",
      "/api/v1/accounting%5Cjournals",
      "/api/v1/hr/me/%zz"})
  void dotSegmentsAndEncodedSeparatorsAreRefused(String path) {
    MockHttpServletRequest request = request(path);

    BaseException e = assertThrows(BaseException.class,
        () -> interceptor.preHandle(request, new MockHttpServletResponse(), null));

    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    verifyNoInteractions(rbacService);
  }

  @Test
  void preflightRequestsAreNotChecked() {
    MockHttpServletRequest request = request("/api/v1/hr/me/../employees");
    request.setMethod("OPTIONS");

    assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));
    verifyNoInteractions(rbacService);
  }

  @Test
  void theResolvedAccessIsKeptOnTheRequest() {
    ShopAccessResponse access = new ShopAccessResponse();
    when(rbacService.requireModule("u1", "s1", RbacService.MODULE_HR)).thenReturn(access);
    MockHttpServletRequest request = request("/api/v1/hr/employees");

    interceptor.preHandle(request, new MockHttpServletResponse(), null);

    assertSame(access, request.getAttribute(RbacService.ACCESS_ATTRIBUTE));
  }

  @Test
  void aMemberWithHrSwitchedOffIsDenied() {
    when(rbacService.requireModule("u1", "s1", RbacService.MODULE_HR))
        .thenThrow(new BaseException(ErrorCode.ACCESS_DENIED, "denied"));
    MockHttpServletRequest request = request("/api/v1/hr/employees");

    assertThrows(BaseException.class,
        () -> interceptor.preHandle(request, new MockHttpServletResponse(), null));
    assertNull(request.getAttribute(RbacService.ACCESS_ATTRIBUTE));
  }

  @Test
  void accountingStillUsesItsOwnModule() {
    when(rbacService.requireModule("u1", "s1", RbacService.MODULE_ACCOUNTING))
        .thenReturn(new ShopAccessResponse());

    interceptor.preHandle(request("/api/v1/accounting/journals"), new MockHttpServletResponse(),
        null);

    verify(rbacService, never()).requireModule(anyString(), anyString(),
        org.mockito.ArgumentMatchers.eq(RbacService.MODULE_HR));
  }
}
