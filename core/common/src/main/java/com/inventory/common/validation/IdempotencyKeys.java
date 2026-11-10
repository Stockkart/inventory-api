package com.inventory.common.validation;

import com.inventory.common.exception.ValidationException;
import org.springframework.util.StringUtils;

/**
 * The one check for an {@code Idempotency-Key}: requests that put something irreversible in motion
 * (a kitchen ticket, a reprint) must carry a non-blank key, so a retry replays instead of repeating.
 */
public final class IdempotencyKeys {

  private IdempotencyKeys() {}

  /** Returns the key, or throws a 400 naming the header when it is missing or blank. */
  public static String require(String idempotencyKey) {
    if (!StringUtils.hasText(idempotencyKey)) {
      throw new ValidationException("Idempotency-Key header is required");
    }
    return idempotencyKey;
  }
}
