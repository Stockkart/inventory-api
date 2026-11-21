package com.inventory.taxation.gstin;

import com.inventory.common.gst.Gstin;
import java.util.Optional;

/**
 * A service that can answer "what does the GST network say about this GSTIN?". One implementation
 * per provider; the registry picks the configured one ({@code gstin.provider}).
 *
 * <p>Implementations translate the provider's own field names into a {@link GstinRecord} and keep
 * everything provider-specific behind this interface, so switching provider is a new class and a
 * property, never a change to callers.
 */
public interface GstinLookupProvider {

  /** Short name stored on each record, e.g. {@code gstinapi.in}. */
  String name();

  /** Whether this provider can actually be called (credentials present). */
  boolean isConfigured();

  /**
   * The network's record, or empty when the GSTIN is unknown to the network.
   *
   * @throws GstinLookupException when the provider could not be reached or answered with an error
   */
  Optional<GstinRecord> lookup(Gstin gstin) throws GstinLookupException;
}
