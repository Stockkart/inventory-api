package com.inventory.hr.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/hr/attendance-app/pairings/me/unpair}: "Remove this phone" done (R3.7). The
 * app wipes its storage on this answer. Mirrors {@code UnpairResponse} in {@code
 * attendance-protocol}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnpairResponse {
  /** Always true: the pairing is unpaired now, by this call or an earlier one. */
  private boolean unpaired;
}
