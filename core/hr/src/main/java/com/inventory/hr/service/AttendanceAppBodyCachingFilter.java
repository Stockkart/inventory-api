package com.inventory.hr.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

/**
 * Buffers the body of every request under {@code /api/v1/hr/attendance-app} so that {@link
 * AttendanceAppAuthInterceptor} can hash the exact body bytes for the request envelope and the
 * controller can still bind {@code @RequestBody} afterwards.
 *
 * <p>Why a filter: an interceptor runs after the servlet container hands Spring the request and
 * cannot replace the request object; once it has read the input stream, argument resolution finds
 * it empty. Spring's {@code ContentCachingRequestWrapper} does not help either, because it only
 * caches what has already been consumed and does not serve the body a second time. So this filter
 * reads the body once, eagerly, and the wrapper serves it from memory on every {@code
 * getInputStream()} / {@code getReader()} call. The codebase had no body-reading filter before this
 * one; the two existing {@code OncePerRequestFilter}s only touch headers.
 *
 * <p>Bodies are small (a punch is well under 1 KB); anything over {@link #MAX_BODY_BYTES} is
 * marked oversized and the interceptor refuses it as not verified. The filter is found by the
 * {@code com.inventory.*} component scan and limits itself to the app paths in {@link
 * #shouldNotFilter}; Bearer-chain requests are never wrapped.
 */
@Component
public class AttendanceAppBodyCachingFilter extends OncePerRequestFilter {

  /** Upper bound of a buffered body; larger bodies cannot be verified. */
  public static final int MAX_BODY_BYTES = 64 * 1024;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return uri == null
        || !(uri.equals(AttendanceAppAuthInterceptor.PATH_PREFIX)
            || uri.startsWith(AttendanceAppAuthInterceptor.PATH_PREFIX + "/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    filterChain.doFilter(new CachedBodyRequest(request), response);
  }

  /**
   * The wrapped request whose body lives in memory. {@link #body()} returns the exact bytes the
   * client sent (empty when the request had none); {@link #oversized()} is true when the body was
   * cut at {@link #MAX_BODY_BYTES} and therefore cannot be hashed.
   */
  public static final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private final boolean oversized;

    CachedBodyRequest(HttpServletRequest request) throws IOException {
      super(request);
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      boolean cut = false;
      try (InputStream in = request.getInputStream()) {
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
          int room = MAX_BODY_BYTES - buffer.size();
          if (n > room) {
            buffer.write(chunk, 0, Math.max(room, 0));
            cut = true;
            break;
          }
          buffer.write(chunk, 0, n);
        }
      }
      this.body = buffer.toByteArray();
      this.oversized = cut;
    }

    /** The exact body bytes; a fresh copy, never shared. */
    public byte[] body() {
      return body.clone();
    }

    public boolean oversized() {
      return oversized;
    }

    @Override
    public ServletInputStream getInputStream() {
      return new ByteArrayServletInputStream(body);
    }

    @Override
    public BufferedReader getReader() {
      String encoding = getCharacterEncoding();
      Charset charset =
          encoding != null && Charset.isSupported(encoding)
              ? Charset.forName(encoding)
              : StandardCharsets.UTF_8;
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }

    /** The wrapper of this request when the filter ran, else null. */
    public static CachedBodyRequest find(HttpServletRequest request) {
      return WebUtils.getNativeRequest(request, CachedBodyRequest.class);
    }
  }

  private static final class ByteArrayServletInputStream extends ServletInputStream {

    private final ByteArrayInputStream delegate;

    private ByteArrayServletInputStream(byte[] bytes) {
      this.delegate = new ByteArrayInputStream(bytes);
    }

    @Override
    public int read() {
      return delegate.read();
    }

    @Override
    public int read(byte[] b, int off, int len) {
      return delegate.read(b, off, len);
    }

    @Override
    public int available() {
      return delegate.available();
    }

    @Override
    public boolean isFinished() {
      return delegate.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      throw new UnsupportedOperationException("Non-blocking IO is not supported");
    }
  }
}
