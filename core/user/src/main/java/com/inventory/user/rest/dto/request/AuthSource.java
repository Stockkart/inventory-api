package com.inventory.user.rest.dto.request;

import com.inventory.common.exception.ValidationException;
import java.util.Optional;
import org.springframework.util.StringUtils;

/**
 * The two clients that sign up and sign in. The value is a metric tag ({@code
 * inventory_user_auth_total{source}}) and an e-mail template variable, so the set is closed: anything
 * else is a 400 (worker-app R1.8).
 */
public final class AuthSource {

  /** The web dashboard; the default when a request names no source. */
  public static final String WEB = "web";

  /** Stockkart Team (the Expo app). */
  public static final String WORKER_APP = "worker-app";

  private AuthSource() {}

  /**
   * @param raw the {@code source} field as sent, possibly {@code null}
   * @return {@link #WEB} when absent, otherwise the trimmed lower-cased value
   * @throws ValidationException when the value is neither {@link #WEB} nor {@link #WORKER_APP}
   */
  public static String normalise(String raw) {
    if (!StringUtils.hasText(raw)) {
      return WEB;
    }
    String value = raw.trim().toLowerCase();
    if (WEB.equals(value) || WORKER_APP.equals(value)) {
      return value;
    }
    throw new ValidationException("source must be either 'web' or 'worker-app'");
  }

  /** {@link #normalise} without the exception: empty when the value is unknown. */
  public static Optional<String> tryNormalise(String raw) {
    try {
      return Optional.of(normalise(raw));
    } catch (ValidationException e) {
      return Optional.empty();
    }
  }
}
