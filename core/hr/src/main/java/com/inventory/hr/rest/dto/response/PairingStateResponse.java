package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.DeviceOs;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code GET /api/v1/hr/employees/{id}/pairing} and {@code GET /api/v1/hr/me/pairing}: the
 * Attendance app section of an employee's page (R2.2). Never carries a key or installation id.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PairingStateResponse {

  /** The two states the section shows. */
  public enum State {
    NOT_PAIRED,
    PAIRED
  }

  private State state;
  /** PAIRED only: as reported by the phone at pairing. */
  private String phoneModel;
  /** PAIRED only. */
  private DeviceOs os;
  /** PAIRED only: when pairing completed. */
  private Instant pairedAt;
  /** PAIRED only: time of the last accepted punch; null = "no punches yet". */
  private Instant lastPunchAt;
  /** PAIRED only: user id of the manager, or {@code "self"}. */
  private String pairedBy;
  /** PAIRED only: the consent version the worker agreed to. */
  private Integer consentVersion;
  /**
   * PAIRED only (task 16.4): true when the phone's key attestation verified, false when a chain was
   * sent and failed, null when the phone sent none (iOS, older app). Informational in this version.
   */
  private Boolean attested;
  /** A code created for this employee that is still valid; null = none. */
  private PendingCode pendingCode;

  /** The unused or claimed code still inside its 15 minutes. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PendingCode {
    private Instant expiresAt;
  }
}
