package com.inventory.hr.rest.dto.request;

import java.util.List;
import lombok.Data;
import lombok.ToString;

/**
 * {@code POST /api/v1/hr/attendance-app/pairings/complete}, "I agree" (R2.9, R5.1). Carries the two
 * public keys the phone made in its secure hardware; the private keys never leave it (design DD2).
 * The claim token is left out of {@code toString}.
 */
@Data
public class CompletePairingRequest {
  /** The token {@code claim} returned; valid until the code expires. */
  @ToString.Exclude private String claimToken;
  /** Must be the same installation that claimed the code. */
  private String installationId;
  /** Base64 DER SubjectPublicKeyInfo of the biometric-gated P-256 key that signs punches. */
  private String punchPublicKey;
  /** Base64 DER SubjectPublicKeyInfo of the no-prompt P-256 key that signs request envelopes. */
  private String devicePublicKey;
  /** The consent version the worker saw and agreed to (R2.8, R9.12). */
  private Integer consentVersion;
  /**
   * Optional (task 16.4): the Android key attestation chain of the punch key, Base64 DER X.509
   * certificates, leaf first, made with the claim token as the attestation challenge. Absent on iOS
   * and on older app builds; absence changes nothing. Verified by {@code KeyAttestation} and
   * recorded on the pairing; a failed verification does not refuse the pairing in this version.
   */
  @ToString.Exclude private List<String> attestationChain;
}
