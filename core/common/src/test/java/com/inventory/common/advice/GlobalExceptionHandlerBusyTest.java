package com.inventory.common.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiError;
import com.inventory.common.dto.response.ApiErrorMapperImpl;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.BaseException;
import com.mongodb.MongoTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

/** No free database connection in time is answered as "busy, retry" (503), not as a crash (500). */
class GlobalExceptionHandlerBusyTest {

  private GlobalExceptionHandler handler;
  private MockHttpServletRequest request;

  @BeforeEach
  void setUp() {
    handler = new GlobalExceptionHandler();
    ReflectionTestUtils.setField(handler, "apiErrorMapper", new ApiErrorMapperImpl());
    request = new MockHttpServletRequest("PUT", "/api/v1/cart/status");
  }

  private static MongoTimeoutException poolTimeout() {
    return new MongoTimeoutException("Timed out after 2000 ms while waiting for a connection to server x:27017");
  }

  @Test
  void aServiceWrappedPoolTimeoutIsBusy() {
    // services catch DataAccessException and wrap it; the driver's timeout sits in the causes
    RuntimeException springWrapper = new RuntimeException("DataAccessResourceFailureException", poolTimeout());
    BaseException wrapped = new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error updating purchase status", springWrapper);

    ResponseEntity<ApiResponse<ApiError>> r = handler.handleBaseException(wrapped, request);

    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
    assertThat(r.getBody().getError()).isEqualTo(ErrorCode.SERVICE_BUSY.getMessage());
    assertThat(r.getBody().getData().getStatus()).isEqualTo(503);
  }

  @Test
  void anUnwrappedPoolTimeoutIsBusyToo() {
    ResponseEntity<ApiResponse<ApiError>> r =
        handler.handleAllUncaughtException(new RuntimeException("query failed", poolTimeout()), request);

    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
  }

  @Test
  void aTimeoutRecordedOnTheRequestIsBusyEvenWithoutTheCause() {
    // many services rethrow BaseException(INTERNAL_SERVER_ERROR, "Failed to search inventory") with no cause
    request.setAttribute(GlobalExceptionHandler.MONGO_BUSY_ATTRIBUTE, Boolean.TRUE);

    ResponseEntity<ApiResponse<ApiError>> r = handler.handleBaseException(
        new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to search inventory"), request);

    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
  }

  @Test
  void otherErrorsAreUnchanged() {
    ResponseEntity<ApiResponse<ApiError>> internal = handler.handleBaseException(
        new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "boom", new IllegalStateException("x")), request);
    ResponseEntity<ApiResponse<ApiError>> stock = handler.handleBaseException(
        new BaseException(ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock"), request);
    ResponseEntity<ApiResponse<ApiError>> uncaught = handler.handleAllUncaughtException(new IllegalStateException("x"), request);

    assertThat(internal.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(internal.getBody().getError()).isEqualTo("boom");
    assertThat(stock.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(uncaught.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(internal.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
  }

  @Test
  void aBusinessErrorCausedByATimeoutKeepsItsOwnStatus() {
    // only INTERNAL_SERVER_ERROR is turned into "busy"; a deliberate 4xx stays what it is
    BaseException notFound = new BaseException(ErrorCode.RESOURCE_NOT_FOUND, "Lot not found", poolTimeout());

    assertThat(handler.handleBaseException(notFound, request).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
