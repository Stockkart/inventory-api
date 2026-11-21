package com.inventory.app.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.user.api.AdminSessions;
import com.inventory.user.api.AdminSessions.AdminPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class AdminAuthenticationInterceptorTest {

  @Mock
  private AdminSessions adminAuthService;

  @InjectMocks
  private AdminAuthenticationInterceptor interceptor;

  @Test
  void validAdminSessionSetsTheActor() {
    when(adminAuthService.authenticate("admin-token")).thenReturn(auth(false));
    MockHttpServletRequest request = request("GET", "/api/v1/admin/plans", "admin-token");

    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
    assertThat(request.getAttribute("adminId")).isEqualTo("a1");
    assertThat(request.getAttribute("userId")).isNull();
  }

  @Test
  void shopUserTokenIsRejected() {
    when(adminAuthService.authenticate("shop-token"))
        .thenThrow(new AuthenticationException(ErrorCode.UNAUTHORIZED, "sign in again"));

    assertThatThrownBy(() -> interceptor.preHandle(request("GET", "/api/v1/admin/plans", "shop-token"),
        new MockHttpServletResponse(), null))
        .isInstanceOf(AuthenticationException.class);
  }

  @Test
  void missingTokenIsRejectedWithoutALookup() {
    assertThatThrownBy(() -> interceptor.preHandle(request("GET", "/api/v1/admin/plans", null),
        new MockHttpServletResponse(), null))
        .isInstanceOf(AuthenticationException.class);
    verifyNoInteractions(adminAuthService);
  }

  @Test
  void pendingPasswordChangeOnlyAllowsTheAccountEndpoints() {
    when(adminAuthService.authenticate("admin-token")).thenReturn(auth(true));

    assertThatThrownBy(() -> interceptor.preHandle(request("GET", "/api/v1/admin/plans", "admin-token"),
        new MockHttpServletResponse(), null))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.PASSWORD_CHANGE_REQUIRED);

    for (String path : new String[] {"/api/v1/admin/auth/me", "/api/v1/admin/auth/change-password",
        "/api/v1/admin/auth/logout"}) {
      assertThat(interceptor.preHandle(request("POST", path, "admin-token"), new MockHttpServletResponse(), null))
          .isTrue();
    }
  }

  @Test
  void pendingPasswordChangeMatchesTheDecodedPath() {
    when(adminAuthService.authenticate("admin-token")).thenReturn(auth(true));

    assertThatThrownBy(() -> interceptor.preHandle(
        request("GET", "/api/v1/admin/auth/me/../../plans", "admin-token"),
        new MockHttpServletResponse(), null))
        .isInstanceOf(BaseException.class);
    assertThat(interceptor.preHandle(request("GET", "/api/v1/admin/auth;x=1/me", "admin-token"),
        new MockHttpServletResponse(), null)).isTrue();
  }

  @Test
  void preflightPassesThrough() {
    assertThat(interceptor.preHandle(request("OPTIONS", "/api/v1/admin/plans", null),
        new MockHttpServletResponse(), null)).isTrue();
  }

  private static MockHttpServletRequest request(String method, String path, String token) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    if (token != null) {
      request.addHeader("Authorization", "Bearer " + token);
    }
    return request;
  }

  private static AdminPrincipal auth(boolean mustChangePassword) {
    return new AdminPrincipal("a1", mustChangePassword);
  }
}
