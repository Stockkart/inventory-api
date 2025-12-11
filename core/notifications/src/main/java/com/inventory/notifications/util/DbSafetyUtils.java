package com.inventory.notifications.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
public final class DbSafetyUtils {

  private DbSafetyUtils() {}

  /**
   * Run supplier and return Optional of result. If supplier throws, log and return Optional.empty().
   * Use when you need to return a single object or null.
   */
  public static <T> Optional<T> safeGet(Supplier<T> supplier, String errorMessage) {
    try {
      return Optional.ofNullable(supplier.get());
    } catch (Exception e) {
      log.error("{}: {}", errorMessage, e.getMessage(), e);
      return Optional.empty();
    }
  }

  /**
   * Run supplier that returns a list-like result. On error return empty list (typed).
   */
  @SuppressWarnings("unchecked")
  public static <T> T safeList(Supplier<T> supplier, String errorMessage) {
    try {
      return supplier.get();
    } catch (Exception e) {
      log.error("{}: {}", errorMessage, e.getMessage(), e);
      return (T) Collections.emptyList();
    }
  }

  /**
   * Run supplier for side-effect DB operations. Returns supplier result if any; logs errors.
   */
  public static <T> T safeRun(Supplier<T> supplier, String errorMessage) {
    try {
      return supplier.get();
    } catch (Exception e) {
      log.error("{}: {}", errorMessage, e.getMessage(), e);
      return null;
    }
  }
}
