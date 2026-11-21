package com.inventory.hr.rest.dto.request;

import lombok.Data;
import lombok.ToString;

/**
 * {@code POST /api/v1/hr/attendance-app/pairings/abandon}: "No, this isn't me" or "Don't agree"
 * (R2.6, R2.8). The code behind the token can never pair afterwards.
 */
@Data
public class AbandonPairingRequest {
  @ToString.Exclude private String claimToken;
}
