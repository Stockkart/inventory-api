package com.inventory.taxation.gstin;

import com.inventory.common.gst.Gstin;
import java.util.Optional;

/**
 * The provider when none is configured: every lookup is "could not verify". Offline checks still
 * run and vendors still save; nothing is blocked on a missing API key.
 */
public class NoopGstinLookupProvider implements GstinLookupProvider {

  @Override
  public String name() {
    return "none";
  }

  @Override
  public boolean isConfigured() {
    return false;
  }

  @Override
  public Optional<GstinRecord> lookup(Gstin gstin) {
    throw new GstinLookupException("No GSTIN lookup provider is configured (gstin.provider)");
  }
}
