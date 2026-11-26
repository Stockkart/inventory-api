package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.DeviceOs;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.UnpairReason;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One installation of the Attendance app connected to one employee (R2.15, D3). The document id is
 * the pairing id the phone sends with every request. At most one {@code ACTIVE} row per {@code
 * (shopId, employeeId)} and per {@code installationId} (partial unique indexes).
 *
 * <p>Privacy (R2.16, R9.1): holds no phone number, IMEI, advertising id, coordinates or biometric
 * data. The only key material is the two <em>public</em> keys; the private keys never leave the
 * phone's secure hardware (R5.1). Public keys and the installation id are left out of {@code
 * toString} so a stray log line never carries them.
 *
 * <p>Retention (R3.10): {@code purgeAt = unpairedAt + 90 days}; a TTL index deletes the row then.
 * Punches made through the pairing keep {@code pairingId} and {@code deviceModel} and are not
 * deleted with it (R3.11).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_device_pairings")
public class DevicePairing {

  /** The pairing id. */
  @Id private String id;
  private String shopId;
  private String employeeId;
  private PairingStatus status;
  /** Base64 DER SubjectPublicKeyInfo, P-256. Verifies punch signatures (biometric-gated key). */
  @ToString.Exclude private String punchPublicKey;
  /** Base64 DER SubjectPublicKeyInfo, P-256. Verifies request envelopes (no-prompt key). */
  @ToString.Exclude private String devicePublicKey;
  /** UUID made by the app on first launch; a reinstall makes a new one (R2.11). */
  @ToString.Exclude private String installationId;
  private DeviceOs os;
  /** As reported by the phone's operating system. */
  private String osVersion;
  /** As reported by the phone's operating system, e.g. "Redmi Note 11". */
  private String phoneModel;
  private String appVersion;
  /** When pairing completed (R2.9). */
  private Instant pairedAt;
  /** Server time the "I agree" was received (R2.9). */
  private Instant consentAt;
  /** Latest consent version the worker agreed to (R9.13). */
  private Integer consentVersion;
  /** Every agreement, oldest first (R9.13). */
  private List<Consent> consents = new ArrayList<>();
  /** User id of the manager, or {@code "self"} for a linked employee pairing from My HR (R2.13). */
  private String pairedBy;
  /** null = still active. */
  private Instant unpairedAt;
  /** User id, {@code "self"} or {@code "system"}; null = still active. */
  private String unpairedBy;
  /** null = still active. */
  private UnpairReason unpairReason;
  /** Exit date at 00:00 Asia/Kolkata when the employee is EXITED with a future date; null = none. */
  private Instant scheduledUnpairAt;
  /** Time of the last accepted punch; null = no punches yet (R2.2). */
  private Instant lastPunchAt;
  /**
   * Android key attestation of the punch key at pairing (task 16.4): true = the chain verified to a
   * Google root with a hardware-backed, biometric-gated P-256 key; false = a chain was sent but a
   * check failed ({@link #attestationFailureReason}); null = no chain was sent (iOS, older app).
   * Recorded only; not yet enforced.
   */
  private Boolean attested;
  /** {@code TrustedEnvironment} or {@code StrongBox} as attested; null when not attempted or unreadable. */
  private String attestationSecurityLevel;
  /** {@code KeyAttestation.Reason} of the first failed check; null when ok or not attempted. */
  private String attestationFailureReason;
  /** {@code unpairedAt + 90 days}; TTL. null while active. */
  private Instant purgeAt;
  private Instant createdAt;

  /** One agreement to a consent version (R9.12–R9.13). */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Consent {
    private Integer version;
    private Instant at;
  }
}
