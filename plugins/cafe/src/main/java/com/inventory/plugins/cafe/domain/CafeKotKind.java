package com.inventory.plugins.cafe.domain;

/**
 * What a ticket tells the station to do.
 *
 * <p>This is the cafe-side carrier of the distinction {@code KotStamp} draws when a ticket is
 * printed. {@code KotStamp} lives in {@code core/documentservice}, which {@code plugins/cafe} does
 * not depend on and must not, so the kind is recorded here and mapped to
 * {@code KotStamp.CANCELLED} by whichever module renders the paper and can see both.
 *
 * <p>It is also the last segment of a KOT's {@code _id}
 * ({@code {flushId or cancelId}:{department}:{kind}}), which is what makes ticket creation
 * idempotent by construction.
 */
public enum CafeKotKind {
  /** Send this food. */
  ISSUE,

  /** Stop this food: the quantity on the lines is how many to cancel, always positive. */
  CANCEL
}
