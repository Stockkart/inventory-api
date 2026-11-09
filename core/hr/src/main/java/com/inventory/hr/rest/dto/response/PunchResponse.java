package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One stored punch ({@code /me/punches}, {@code /attendance/punches}), oldest first. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PunchResponse {
  private String id;
  private String employeeId;
  /** Server time of the punch. */
  private Instant at;
  /** HH:mm of {@code at}, Asia/Kolkata. */
  private String time;
  private String localDate;
  private PunchSource source;
  private PunchDirection direction;
  /** Whole metres from the shop; null when the location check was off or no location given. */
  private Integer distanceMetres;
  private Integer accuracyMetres;
  private List<PunchFlag> flags;
  /** false = within 5 minutes of the previous counted punch, so it does not count (R4.2). */
  private boolean counted;
}
