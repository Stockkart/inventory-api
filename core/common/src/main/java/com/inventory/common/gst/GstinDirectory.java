package com.inventory.common.gst;

import java.util.Optional;

/**
 * What the application knows about GSTINs. The implementation (in the taxation module) keeps a
 * registry of every GSTIN ever looked up and asks the GST network only for ones it has not seen,
 * so a lookup costs a network call once per GSTIN, system-wide.
 *
 * <p>Declared here so modules that cannot depend on taxation (vendors, purchases) can still ask.
 */
public interface GstinDirectory {

  /**
   * Whether online verification is switched on (a provider is configured). When it is not, the
   * GSTIN field keeps its old behaviour everywhere: free text, no check-character test, no
   * "GSTIN or state" rule — nothing changes for shops until the feature is turned on.
   */
  boolean isVerificationEnabled();

  /** The registration if the network has been asked before; never calls out. */
  Optional<GstinRegistration> find(String gstin);

  /**
   * The registration, asking the network when it is not on record yet. Empty when the GSTIN is not
   * valid, is unknown to the network, or the network could not be reached — callers decide whether
   * that blocks them (it should not block saving a vendor).
   */
  Optional<GstinRegistration> lookup(String gstin);

  /** Ask the network again and replace what is on record. Empty when it could not be reached. */
  Optional<GstinRegistration> refresh(String gstin);
}
