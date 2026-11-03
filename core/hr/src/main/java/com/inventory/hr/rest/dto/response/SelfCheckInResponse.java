package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchFlag;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code GET /api/v1/hr/me/check-in} (state of the button) and {@code POST} (the same after the
 * punch, plus the punch's {@code flags} and {@code distanceMetres}).
 */
@Data
@NoArgsConstructor
public class SelfCheckInResponse {
  /** Self check-in is on in HR settings. */
  private boolean enabled;
  /** The browser must send its location (location check on). */
  private boolean locationRequired;
  /** The server would accept a punch now (location aside: it is checked on the punch). */
  private boolean canCheckIn;
  /** Why not, in plain words; null when {@code canCheckIn}. */
  private String reason;
  /** HH:mm of the first counted punch today; null = none. */
  private String todayIn;
  /** HH:mm of the last counted punch today when there are two or more; null = none. */
  private String todayOut;
  /** IN when nobody has a counted punch today yet, else OUT. */
  private PunchDirection nextDirection;
  /** Counted punches today (a punch within 5 minutes of the previous one does not count). */
  private int punchCount;
  /** One counted punch and the working day is over (R4.4). */
  private boolean missingCheckOut;
  /** POST only: flags of the stored punch (FLAG mode); null on GET. */
  private List<PunchFlag> flags;
  /** POST only: whole metres from the shop when the location check is on; null otherwise. */
  private Integer distanceMetres;
}
