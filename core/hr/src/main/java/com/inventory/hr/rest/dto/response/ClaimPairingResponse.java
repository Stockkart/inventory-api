package com.inventory.hr.rest.dto.response;

import com.inventory.hr.service.ConsentVersions;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/hr/attendance-app/pairings/claim}: the names the phone shows under "Yes,
 * this is me" (R2.6), the consent text to show next (R2.8), and the short-lived claim token that
 * {@code complete} or {@code abandon} must present.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClaimPairingResponse {
  /** 32 random bytes, Base64url; valid until the code expires. The server stores only its hash. */
  private String claimToken;
  private String shopName;
  private String employeeName;
  private ConsentVersions.ConsentVersion consent;
}
