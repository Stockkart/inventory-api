package com.inventory.app.interceptor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.AuthenticationException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthenticationInterceptorTest {

  private final AuthenticationInterceptor interceptor = new AuthenticationInterceptor();

  @Test
  void legacyPaymentSuccessWebhookRequiresAuthentication() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/plans/webhook/payment-success");
    request.setContentType("application/json");
    request.setContent("{\"shopId\":\"s1\",\"planId\":\"p1\",\"durationMonths\":1200}".getBytes());

    assertThrows(AuthenticationException.class,
        () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
  }

  @Test
  void signatureVerifiedProviderWebhookStaysPublic() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/plans/payment/webhook/razorpay");

    assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
  }
}
