package com.inventory.app.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.web.util.UrlPathHelper;

/**
 * The request path the interceptors match their rules against.
 *
 * <p>{@code getRequestURI()} is the raw URI: still percent-encoded and with {@code ;param} content.
 * Spring MVC (PathPatternParser, the Boot 3 default) routes on that URI split into segments, each
 * one decoded and with its {@code ;param} part dropped, and it does not resolve {@code .} or
 * {@code ..} segments (they can even bind into a {@code {var}}). So {@code
 * /api/v1/accounting;jsessionid=1/journals} reaches the accounting controller although it does not
 * start with {@code /api/v1/accounting/}. Matching on the decoded, semicolon-free path closes that
 * gap; paths that still carry dot segments or encoded separators are refused outright, because the
 * container and Spring would disagree about where they lead.
 */
final class RequestPaths {

  // Decodes as UTF-8 like Spring's routing does, whatever charset the request claims, so a
  // client cannot change how the path decodes and slip past a rule.
  private static final UrlPathHelper PATH_HELPER = new UrlPathHelper() {
    @Override
    protected String determineEncoding(HttpServletRequest request) {
      return StandardCharsets.UTF_8.name();
    }
  };

  static {
    PATH_HELPER.setUrlDecode(true);
    PATH_HELPER.setRemoveSemicolonContent(true);
  }

  private RequestPaths() {}

  /**
   * Decoded path within the application: context path removed, {@code ;param} content removed,
   * percent-escapes decoded and {@code //} collapsed (UrlPathHelper). Null when the URI cannot be
   * decoded, which callers treat as "suspicious".
   */
  static String lookupPath(HttpServletRequest request) {
    try {
      return PATH_HELPER.getPathWithinApplication(request);
    } catch (RuntimeException e) {
      // e.g. a malformed %-escape; Tomcat normally rejects these first
      return null;
    }
  }

  /**
   * True when rules cannot be matched safely: undecodable, a {@code .} or {@code ..} segment after
   * decoding ({@code %2e%2e} included), a backslash, or an encoded {@code /} or {@code \} in the
   * raw URI (Tomcat answers those with 400 by default, so refusing them breaks nothing).
   * Verified in run B5 against embedded Tomcat 10.1.24: {@code %5C}, {@code %5c}, a literal
   * {@code \} and {@code %2F} are all 400 before any interceptor, for every module's paths.
   */
  static boolean isSuspicious(HttpServletRequest request, String lookupPath) {
    if (lookupPath == null || lookupPath.indexOf('\\') >= 0) {
      return true;
    }
    String raw = request.getRequestURI();
    if (raw != null) {
      String lower = raw.toLowerCase(Locale.ROOT);
      if (lower.contains("%2f") || lower.contains("%5c")) {
        return true;
      }
    }
    for (String segment : lookupPath.split("/", -1)) {
      if (segment.equals(".") || segment.equals("..")) {
        return true;
      }
    }
    return false;
  }
}
