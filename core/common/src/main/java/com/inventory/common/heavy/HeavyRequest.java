package com.inventory.common.heavy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as heavy: it runs only when {@link HeavyRequestLimiter} has a free slot
 * of this kind (it waits in a short line, else the caller gets 503 "busy"). The method must do all its
 * work before returning (build the bytes, not a stream), so the slot covers the real work.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HeavyRequest {
  HeavyKind value();
}
