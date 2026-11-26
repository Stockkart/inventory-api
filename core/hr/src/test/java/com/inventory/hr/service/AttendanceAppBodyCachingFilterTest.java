package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.service.AttendanceAppBodyCachingFilter.CachedBodyRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The body is buffered once for the app paths only and can be read again by the controller. */
class AttendanceAppBodyCachingFilterTest {

  private final AttendanceAppBodyCachingFilter filter = new AttendanceAppBodyCachingFilter();

  @Test
  void appRequestIsWrappedAndTheBodyIsReadableTwice() throws Exception {
    byte[] body = "{\"punchId\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest raw =
        new MockHttpServletRequest("POST", "/api/v1/hr/attendance-app/punches");
    raw.setContent(body);
    raw.setCharacterEncoding("UTF-8");

    HttpServletRequest wrapped = run(raw);
    CachedBodyRequest cached = CachedBodyRequest.find(wrapped);

    assertNotNull(cached);
    assertFalse(cached.oversized());
    assertArrayEquals(body, cached.body());
    assertArrayEquals(body, wrapped.getInputStream().readAllBytes());
    assertEquals(new String(body, StandardCharsets.UTF_8), wrapped.getReader().readLine());
    assertEquals(body.length, wrapped.getContentLength());
  }

  @Test
  void requestWithoutABodyHasAnEmptyBody() throws Exception {
    HttpServletRequest wrapped =
        run(new MockHttpServletRequest("GET", "/api/v1/hr/attendance-app/me"));

    assertArrayEquals(new byte[0], CachedBodyRequest.find(wrapped).body());
    assertTrue(wrapped.getInputStream().isFinished());
  }

  @Test
  void oversizedBodyIsMarked() throws Exception {
    byte[] body = new byte[AttendanceAppBodyCachingFilter.MAX_BODY_BYTES + 1];
    Arrays.fill(body, (byte) 'a');
    MockHttpServletRequest raw =
        new MockHttpServletRequest("POST", "/api/v1/hr/attendance-app/punches");
    raw.setContent(body);

    CachedBodyRequest cached = CachedBodyRequest.find(run(raw));

    assertTrue(cached.oversized());
    assertEquals(AttendanceAppBodyCachingFilter.MAX_BODY_BYTES, cached.body().length);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "/api/v1/hr/employees",
      "/api/v1/hr/attendance-apps/x",
      "/api/v1/hr/me/pairing",
      "/api/v1/auth/login"})
  void otherPathsAreNotWrapped(String path) throws Exception {
    MockHttpServletRequest raw = new MockHttpServletRequest("POST", path);
    raw.setContent("{}".getBytes(StandardCharsets.UTF_8));

    HttpServletRequest passed = run(raw);

    assertNull(CachedBodyRequest.find(passed));
    assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), passed.getInputStream().readAllBytes());
  }

  private HttpServletRequest run(MockHttpServletRequest raw) throws Exception {
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(raw, new MockHttpServletResponse(), chain);
    return (HttpServletRequest) chain.getRequest();
  }
}
