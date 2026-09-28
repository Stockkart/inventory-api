package com.inventory.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.inventory.app.interceptor.AdminAuthenticationInterceptor;
import com.inventory.app.interceptor.AuthenticationInterceptor;
import com.inventory.app.interceptor.EntitlementInterceptor;
import com.inventory.app.interceptor.PlanExpiryInterceptor;
import com.inventory.app.interceptor.RbacModuleInterceptor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

/** Admin paths are guarded only by admin sessions; shop paths only by shop-user tokens. */
class CorsConfigInterceptorWiringTest {

  private final AdminAuthenticationInterceptor admin = mock(AdminAuthenticationInterceptor.class);
  private final AuthenticationInterceptor shop = mock(AuthenticationInterceptor.class);
  private final RbacModuleInterceptor rbac = mock(RbacModuleInterceptor.class);
  private final PlanExpiryInterceptor planExpiry = mock(PlanExpiryInterceptor.class);
  private final EntitlementInterceptor entitlement = mock(EntitlementInterceptor.class);

  @Test
  void adminPathsRunOnlyTheAdminInterceptor() {
    assertThat(interceptorsFor("/api/v1/admin/plans")).containsExactly(admin);
    assertThat(interceptorsFor("/api/v1/admin/admins/a1/active")).containsExactly(admin);
    assertThat(interceptorsFor("/api/v1/shops/admin/shops/s1/approve")).containsExactly(admin);
  }

  @Test
  void adminLoginIsPublic() {
    assertThat(interceptorsFor("/api/v1/admin/auth/login")).isEmpty();
  }

  @Test
  void shopPathsNeverRunTheAdminInterceptor() {
    assertThat(interceptorsFor("/api/v1/shops/active-shop"))
        .contains(shop, rbac, planExpiry, entitlement)
        .doesNotContain(admin);
  }

  private List<HandlerInterceptor> interceptorsFor(String path) {
    CorsConfig config = new CorsConfig();
    ReflectionTestUtils.setField(config, "adminAuthenticationInterceptor", admin);
    ReflectionTestUtils.setField(config, "authenticationInterceptor", shop);
    ReflectionTestUtils.setField(config, "rbacModuleInterceptor", rbac);
    ReflectionTestUtils.setField(config, "planExpiryInterceptor", planExpiry);
    ReflectionTestUtils.setField(config, "entitlementInterceptor", entitlement);
    ExposedRegistry registry = new ExposedRegistry();
    config.addInterceptors(registry);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    ServletRequestPathUtils.parseAndCache(request);
    return registry.mapped().stream()
        .filter(m -> m.matches(request))
        .map(MappedInterceptor::getInterceptor)
        .toList();
  }

  private static class ExposedRegistry extends InterceptorRegistry {
    List<MappedInterceptor> mapped() {
      return getInterceptors().stream().map(MappedInterceptor.class::cast).toList();
    }
  }
}
