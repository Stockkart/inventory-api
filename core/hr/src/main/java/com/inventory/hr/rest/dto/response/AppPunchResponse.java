package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchFlag;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/hr/attendance-app/punches}: what the confirmation screen shows after a stored
 * punch (R4.7–R4.8). Mirrors {@code AppPunchResponse} in {@code attendance-protocol}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppPunchResponse {

  /** How the server answered this send. */
  public enum Outcome {
    /** Stored now and counted for the day. */
    STORED,
    /** Stored now, but within 5 minutes of a counted punch, so the day's times did not change. */
    DUPLICATE,
    /** The same punch id was already stored with the same content: the stored result (R5.10). */
    REPLAYED
  }

  private Outcome outcome;
  /** Decided by the server from the day's stored punches; any phone-sent direction is ignored. */
  private PunchDirection direction;
  /** Server time for an online punch, the phone's time for an offline one (R4.4, R6.9). */
  private Instant at;
  /** HH:mm of {@code at}, Asia/Kolkata. */
  private String atLocal;
  private List<PunchFlag> flags;
  /** true = within 5 minutes of a counted punch; the day's times did not change (R4.8). */
  private boolean duplicate;
  /** HH:mm of the first counted punch of the punch's day; null = none. */
  private String todayIn;
  /** HH:mm of the last counted punch of the punch's day when there are two or more. */
  private String todayOut;
  /** IN when the punch's day has no counted punch, else OUT. */
  private PunchDirection nextDirection;
}
