package com.inventory.app.interceptor;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.inventory.common.entitlement.PlanFeature;
import com.inventory.common.entitlement.RequiresEntitlement;
import com.inventory.plan.service.EntitlementGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

@ExtendWith(MockitoExtension.class)
class EntitlementInterceptorTest {

  @Mock
  private EntitlementGuard entitlementGuard;

  @InjectMocks
  private EntitlementInterceptor interceptor;

  @RequiresEntitlement(PlanFeature.ACCOUNTING)
  static class AccountingLikeController {
    public void list() {}

    @RequiresEntitlement(PlanFeature.SALARY)
    public void payroll() {}
  }

  static class OpenController {
    public void list() {}
  }

  @Test
  void checksClassLevelFeature() throws Exception {
    interceptor.preHandle(request("GET"), new MockHttpServletResponse(),
        handler(new AccountingLikeController(), "list"));

    verify(entitlementGuard).requireFeature("shop-1", PlanFeature.ACCOUNTING);
  }

  @Test
  void methodLevelOverridesClassLevel() throws Exception {
    interceptor.preHandle(request("GET"), new MockHttpServletResponse(),
        handler(new AccountingLikeController(), "payroll"));

    verify(entitlementGuard).requireFeature("shop-1", PlanFeature.SALARY);
  }

  @Test
  void ignoresUnannotatedHandlersAndPreflight() throws Exception {
    interceptor.preHandle(request("GET"), new MockHttpServletResponse(), handler(new OpenController(), "list"));
    interceptor.preHandle(request("OPTIONS"), new MockHttpServletResponse(),
        handler(new AccountingLikeController(), "list"));

    verifyNoInteractions(entitlementGuard);
  }

  private static MockHttpServletRequest request(String method) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/accounting/x");
    request.setAttribute("shopId", "shop-1");
    return request;
  }

  private static HandlerMethod handler(Object bean, String method) throws NoSuchMethodException {
    return new HandlerMethod(bean, bean.getClass().getMethod(method));
  }
}
