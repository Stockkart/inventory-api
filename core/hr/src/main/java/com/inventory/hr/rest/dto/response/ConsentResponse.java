package com.inventory.hr.rest.dto.response;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/hr/attendance-app/consent}: the agreement as now stored on the pairing
 * (R9.13). Mirrors {@code ConsentResponse} in {@code attendance-protocol}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsentResponse {
  /** The pairing's consent version after this call; never lower than before. */
  private int agreedVersion;
  /** Server time the agreement was received. */
  private Instant at;
}
