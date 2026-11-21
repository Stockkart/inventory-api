package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.DeviceOs;
import com.inventory.hr.domain.model.enums.PairingCodeStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A one-time pairing code (R2.3–R2.5). The code itself is never stored: only its SHA-256 hash. The
 * plaintext exists on the manager's screen and in the QR for 15 minutes.
 *
 * <p>Retention (R9.11): deleted when used, or by the TTL index on {@code expiresAt} ({@code
 * createdAt + 15 min}), whichever comes first. Because a completed code is deleted, re-typing it
 * afterwards matches no document and reads as "code wrong" ({@code HR_PAIRING_CODE_WRONG}), not
 * "already used"; {@code HR_PAIRING_CODE_USED} is answered only while the document still exists
 * (claimed by another phone, or in the moment before deletion). Hashes and the installation id are
 * left out of {@code toString}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_pairing_codes")
public class PairingCode {

  @Id private String id;
  private String shopId;
  private String employeeId;
  /** SHA-256 hex of the normalised 8-character code; unique. */
  @ToString.Exclude private String codeHash;
  private PairingCodeStatus status;
  /** User id of the manager, or {@code "self"} (R2.13). */
  private String createdBy;
  private Instant createdAt;
  /** {@code createdAt + 15 min}; TTL (R2.3, R9.11). */
  private Instant expiresAt;
  /**
   * Claim or complete calls that reached this document and were refused or claimed it; the 11th
   * sets {@link PairingCodeStatus#INVALIDATED} (R9.10). null = 0.
   */
  private Integer attempts;
  /** SHA-256 hex of the claim token handed to the phone that claimed the code; null = unclaimed. */
  @ToString.Exclude private String claimTokenHash;
  /** Installation that claimed the code; null = unclaimed. */
  @ToString.Exclude private String claimedByInstallationId;
  /** null = unclaimed. */
  private Instant claimedAt;
  /** Phone details sent with the claim, copied onto the pairing at completion (R2.15). */
  private DeviceOs claimedOs;
  private String claimedOsVersion;
  private String claimedPhoneModel;
  private String claimedAppVersion;
  /** The pairing this code completed; null until {@link PairingCodeStatus#USED}. */
  private String pairingId;
}
