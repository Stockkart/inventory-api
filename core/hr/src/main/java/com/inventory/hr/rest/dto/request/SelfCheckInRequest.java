package com.inventory.hr.rest.dto.request;

import lombok.Data;
import lombok.ToString;

/**
 * {@code POST /api/v1/hr/me/check-in}. Every field is optional; they are read only when the shop's
 * location check is on. The server works out the distance itself (a client distance would be
 * trivially faked) and then drops the coordinates: they are never stored, logged or audited
 * (R3.6), and are left out of {@code toString} so no log line can print them by accident.
 */
@Data
public class SelfCheckInRequest {
  /** Degrees, −90..90. */
  @ToString.Exclude private Double lat;
  /** Degrees, −180..180. */
  @ToString.Exclude private Double lng;
  /** Metres, as the browser reports it ({@code coords.accuracy}). */
  private Double accuracy;
}
