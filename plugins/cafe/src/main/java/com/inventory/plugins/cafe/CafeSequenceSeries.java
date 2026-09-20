package com.inventory.plugins.cafe;

/**
 * Independent daily counters for the cafe vertical.
 *
 * <p>Token numbering lives in {@link CafeTokenService} against its own collection and is
 * deliberately not part of this enum — adding a series discriminator to that collection would
 * change its unique key and require migrating live data.
 */
public enum CafeSequenceSeries {
  ORDER,
  KOT
}
