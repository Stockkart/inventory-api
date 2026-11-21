package com.inventory.common.advice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiErrorMapperImpl;
import com.inventory.common.exception.BaseException;
import com.inventory.common.heavy.HeavyKind;
import com.inventory.common.heavy.HeavyRequestBusyException;
import com.mongodb.MongoTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Error answers are JSON whatever the request accepts. An SSE open sends {@code Accept:
 * text/event-stream}; with content negotiation the JSON error could not be written and a 503
 * "busy" became a 500 (seen with 1,000 SSE opens at once).
 */
class GlobalExceptionHandlerAcceptTest {

  @RestController
  static class Endpoints {
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
      throw new RuntimeException("query failed",
          new MongoTimeoutException("Timed out after 2000 ms while waiting for a connection to server x:27017"));
    }

    @GetMapping(value = "/stream-denied", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter denied() {
      throw new BaseException(ErrorCode.ACCESS_DENIED, "No access to this shop");
    }

    @GetMapping("/plain")
    String plain() {
      throw new BaseException(ErrorCode.ACCESS_DENIED, "No access to this shop");
    }

    @GetMapping(value = "/report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    byte[] pdf() {
      throw new HeavyRequestBusyException(HeavyKind.EXPORT);
    }
  }

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    ReflectionTestUtils.setField(handler, "apiErrorMapper", new ApiErrorMapperImpl());
    mvc = MockMvcBuilders.standaloneSetup(new Endpoints()).setControllerAdvice(handler).build();
  }

  @Test
  void anSseOpenThatFindsNoConnectionIs503NotA500() throws Exception {
    mvc.perform(get("/stream").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "2"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error").value(ErrorCode.SERVICE_BUSY.getMessage()));
  }

  @Test
  void otherErrorsOnAnSseOpenKeepTheirStatus() throws Exception {
    mvc.perform(get("/stream-denied").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("No access to this shop"));
  }

  @Test
  void aDownloadAskingOnlyForPdfGetsTheBusyMessage() throws Exception {
    mvc.perform(get("/report.pdf").accept(MediaType.APPLICATION_PDF))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "30"))
        .andExpect(jsonPath("$.error").value(
            "Another report is being prepared. Please try again in a minute."));
  }

  @Test
  void jsonClientsSeeNoChange() throws Exception {
    mvc.perform(get("/plain").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
  }
}
