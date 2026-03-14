package com.inventory.reminders.utils;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Utility methods for SSE emitter operations.
 */
public final class SseEmitterUtils {

  private SseEmitterUtils() {}

  /**
   * Safely completes an emitter. Ignores IllegalStateException if already completed.
   */
  public static void safeComplete(SseEmitter emitter) {
    try {
      emitter.complete();
    } catch (IllegalStateException ignore) {
      // Already completed or disconnected
    }
  }
}
