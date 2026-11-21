package com.inventory.hr.rest.dto.request;

import com.inventory.hr.domain.model.enums.DeviceOs;
import lombok.Data;
import lombok.ToString;

/**
 * {@code POST /api/v1/hr/attendance-app/pairings/claim}, step one of the handshake (R2.6, design
 * DD7). Unsigned: the phone has no key yet. The code is the only secret here and is left out of
 * {@code toString} so no log line prints it.
 */
@Data
public class ClaimPairingRequest {
  /** The 8 characters scanned or typed; case, spaces and hyphens do not matter (R2.3). */
  @ToString.Exclude private String code;
  /** UUID the app made on first launch; a reinstall makes a new one (R2.11). */
  private String installationId;
  private DeviceOs os;
  /** As reported by the phone's operating system, e.g. {@code 14}. */
  private String osVersion;
  /** As reported by the phone's operating system, e.g. {@code Redmi Note 11}; at most 80 chars. */
  private String phoneModel;
  private String appVersion;
}
