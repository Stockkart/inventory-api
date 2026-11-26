package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.OutsideAction;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The shop's Location check settings as the Attendance app needs them (design DD6): returned to a
 * paired phone at pairing completion and on every status call, so the phone can work out its
 * distance from the shop itself and never send coordinates (R9.2).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppLocationSettingsResponse {
  private boolean locationCheck;
  /** null when the location check is off or the shop location is not set. */
  private Double shopLat;
  /** null when the location check is off or the shop location is not set. */
  private Double shopLng;
  private Integer radiusMetres;
  private OutsideAction outsideAction;
}
