package com.inventory.plugins.cafe;

/**
 * Independent daily counters for the cafe vertical.
 *
 * <p>Token numbering lives in {@link CafeTokenService} against its own collection and is
 * deliberately not part of this enum — adding a series discriminator to that collection would
 * change its unique key and require migrating live data.
 *
 * <p>One series, because one is all anything allocates. The discriminator stays in the
 * collection's unique key ({@code shopId, businessDate, series}) so a second counter can be added
 * without migrating live rows; the retired punch model's ORDER series had no allocator left.
 */
public enum CafeSequenceSeries {
  KOT
}
