package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The Bearer half of worker-app R9.1 (design DD3, DD6, Property 21): a request to {@value
 * #PATH_PATTERN} that also carries a device-envelope header ({@code X-Pairing-Id} or {@code
 * X-Request-Signature}) is refused with {@code HR_TWO_CREDENTIALS} before any handler, and before
 * the token is even looked at. Registered in {@code CorsConfig} on exactly this prefix, ahead of
 * the Bearer chain; the mirror rule for a device request carrying {@code Authorization} lives in
 * {@link AttendanceAppAuthInterceptor}.
 *
 * <p>Nothing is read or written here: no account, no pairing, no counter. The two envelope headers
 * are enough to know the app mixed its clients; the timestamp and nonce alone mean nothing without
 * them.
 */
@Slf4j
@Component
public class WorkerCredentialInterceptor implements HandlerInterceptor {

  /** Path prefix of every signed-in worker endpoint ({@code WorkerController}). */
  public static final String PATH_PREFIX = "/api/v1/hr/worker";

  /** The pattern this interceptor is registered on. */
  public static final String PATH_PATTERN = PATH_PREFIX + "/**";

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
      Object handler) {
    if ("OPTIONS".equals(request.getMethod())) {
      return true;
    }
    if (StringUtils.hasText(request.getHeader(AttendanceAppAuthInterceptor.HEADER_PAIRING_ID))
        || StringUtils.hasText(
            request.getHeader(AttendanceAppAuthInterceptor.HEADER_SIGNATURE))) {
      log.warn("Worker request refused: it carried a device envelope header too");
      throw new BaseException(ErrorCode.HR_TWO_CREDENTIALS,
          ErrorCode.HR_TWO_CREDENTIALS.getMessage());
    }
    return true;
  }
}
