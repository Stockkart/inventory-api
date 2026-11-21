package com.inventory.app.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.user.api.PlatformAdmins;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PlatformRoleInterceptorTest {

  private final PlatformRoleInterceptor interceptor = new PlatformRoleInterceptor();

  {
    PlatformAdmins admins = "admin-1"::equals;
    org.springframework.test.util.ReflectionTestUtils.setField(interceptor, "platformAdmins", admins);
  }

  @Test
  void lets_platform_admin_through() {
    MockHttpServletRequest request = request("POST", "/api/v1/admin/plans", "admin-1");

    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
  }

  @Test
  void rejects_shop_user_on_admin_path() {
    MockHttpServletRequest request = request("POST", "/api/v1/admin/plans", "user-1");

    assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.ACCESS_DENIED);
  }

  @Test
  void leaves_shop_approval_to_the_super_user_check() {
    MockHttpServletRequest request = request("POST",
        "/api/v1/shops/admin/shops/64b7f0c2a1b2c3d4e5f60718/approve", "user-1");

    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
  }

  @Test
  void rejects_request_without_account() {
    MockHttpServletRequest request = request("GET", "/api/v1/admin/audit", null);

    assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
        .isInstanceOf(BaseException.class);
  }

  @Test
  void ignores_non_admin_paths_and_preflight() {
    assertThat(interceptor.preHandle(request("GET", "/api/v1/shops/active-shop", "user-1"),
        new MockHttpServletResponse(), null)).isTrue();
    assertThat(interceptor.preHandle(request("OPTIONS", "/api/v1/admin/plans", null),
        new MockHttpServletResponse(), null)).isTrue();
  }

  @Test
  void admin_prefix_does_not_match_lookalike_paths() {
    assertThat(PlatformRoleInterceptor.isAdminPath("/api/v1/administrators")).isFalse();
    assertThat(PlatformRoleInterceptor.isAdminPath("/api/v1/accounting/admin/backfill")).isFalse();
    assertThat(PlatformRoleInterceptor.isAdminPath("/api/v1/admin/plans")).isTrue();
  }

  @Test
  void matches_the_decoded_path() {
    MockHttpServletRequest request = request("GET", "/api/v1/admin;x=1/plans", "user-1");

    assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
        .isInstanceOf(BaseException.class);
  }

  private static MockHttpServletRequest request(String method, String path, String userId) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    if (userId != null) {
      request.setAttribute("userId", userId);
    }
    return request;
  }
}
