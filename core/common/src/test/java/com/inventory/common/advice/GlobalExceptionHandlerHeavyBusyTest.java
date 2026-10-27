package com.inventory.common.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.common.dto.response.ApiError;
import com.inventory.common.dto.response.ApiErrorMapperImpl;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.heavy.HeavyKind;
import com.inventory.common.heavy.HeavyRequestBusyException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

/** No heavy-request slot in time is a 503 with the kind's message and Retry-After, in the usual shape. */
class GlobalExceptionHandlerHeavyBusyTest {

  @Test
  void busyIs503WithTheKindsMessageAndRetryAfter() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    ReflectionTestUtils.setField(handler, "apiErrorMapper", new ApiErrorMapperImpl());

    ResponseEntity<ApiResponse<ApiError>> r = handler.handleHeavyRequestBusy(
        new HeavyRequestBusyException(HeavyKind.EXPORT), new MockHttpServletRequest("GET", "/api/v1/mis/sales/pdf"));

    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
    assertThat(r.getBody().getError()).isEqualTo("Another report is being prepared. Please try again in a minute.");
    assertThat(r.getBody().getData().getStatus()).isEqualTo(503);
  }
}
