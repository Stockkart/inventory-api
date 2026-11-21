package com.inventory.hr.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/hr/attendance-app/pairings/complete}: everything the app stores about its
 * pairing (R2.9, R9.3). The pairing id is what the phone sends with every later request.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PairingBootstrapResponse {
  private String pairingId;
  private String shopId;
  private String employeeId;
  private String employeeName;
  private String shopName;
  private AppLocationSettingsResponse location;
  /** The consent version the worker just agreed to. */
  private Integer consentVersion;
}
