package com.inventory.common.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.common.dto.response.ApiErrorMapperImpl;
import com.inventory.common.dto.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** An upload over the request limit is a clear 413 in the app's usual error shape, not a 500. */
class GlobalExceptionHandlerUploadSizeTest {

  @Test
  void tooLargeUploadIs413WithTheLimit() throws Exception {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    ReflectionTestUtils.setField(handler, "apiErrorMapper", new ApiErrorMapperImpl());
    ServletWebRequest request =
        new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/inventory/parse-invoice"));

    ResponseEntity<Object> r =
        handler.handleException(new MaxUploadSizeExceededException(10L * 1024 * 1024), request);

    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    ApiResponse<?> body = (ApiResponse<?>) r.getBody();
    assertThat(body.getError()).isEqualTo("Upload too large: the limit is 10 MB per upload");
  }

  @Test
  void theConfiguredLimitIsNamedWhenTheExceptionHasNone() throws Exception {
    // Tomcat's request-size breach reaches Spring with an unknown (-1) limit
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    ReflectionTestUtils.setField(handler, "apiErrorMapper", new ApiErrorMapperImpl());
    ReflectionTestUtils.setField(handler, "maxRequestSize", DataSize.ofMegabytes(10));

    ResponseEntity<Object> r = handler.handleException(
        new MaxUploadSizeExceededException(-1),
        new ServletWebRequest(new MockHttpServletRequest("POST", "/m/upload")));

    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(((ApiResponse<?>) r.getBody()).getError()).isEqualTo("Upload too large: the limit is 10 MB per upload");
  }
}
