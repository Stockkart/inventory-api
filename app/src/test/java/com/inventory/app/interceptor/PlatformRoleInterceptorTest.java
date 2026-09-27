package com.inventory.app.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.user.domain.model.PlatformRole;
import com.inventory.user.domain.model.UserAccount;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PlatformRoleInterceptorTest {

  private final PlatformRoleInterceptor interceptor = new PlatformRoleInterceptor();

  @Test
  void lets_platform_admin_through() {
    MockHttpServletRequest request = request("POST", "/api/v1/admin/plans", account(PlatformRole.PLATFORM_ADMIN));

    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
  }

  @Test
  void rejects_shop_user_on_admin_path() {
    MockHttpServletRequest request = request("POST", "/api/v1/admin/plans", account());

    assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.ACCESS_DENIED);
  }

  @Test
  void rejects_shop_user_on_legacy_shop_approval_path() {
    MockHttpServletRequest request = request("POST",
        "/api/v1/shops/admin/shops/64b7f0c2a1b2c3d4e5f60718/approve", account());

    assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
        .isInstanceOf(BaseException.class);
  }

  @Test
  void rejects_request_without_account() {
    MockHttpServletRequest request = request("GET", "/api/v1/admin/audit", null);

    assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
        .isInstanceOf(BaseException.class);
  }

  @Test
  void ignores_non_admin_paths_and_preflight() {
    assertThat(interceptor.preHandle(request("GET", "/api/v1/shops/active-shop", account()),
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

  private static MockHttpServletRequest request(String method, String path, UserAccount account) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    if (account != null) {
      request.setAttribute("userAccount", account);
      request.setAttribute("userId", account.getUserId());
    }
    return request;
  }

  private static UserAccount account(PlatformRole... roles) {
    UserAccount account = new UserAccount();
    account.setUserId("user-1");
    account.setPlatformRoles(roles.length == 0 ? null : Set.of(roles));
    return account;
  }
}
