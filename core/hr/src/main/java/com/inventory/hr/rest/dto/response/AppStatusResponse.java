package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.PunchDirection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code GET /api/v1/hr/attendance-app/me}: what the home screen shows (R4.1). The phone never works
 * out the button label itself while online; it uses {@code nextDirection} from here (R4.1a–b).
 * Mirrors {@code AppStatusResponse} in {@code attendance-protocol}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppStatusResponse {

  /** Consent state (R9.13): the app shows the latest version first when re-consent is required. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ConsentState {
    private int latestVersion;
    /** A newer "re-consent required" version exists than the one the worker agreed to. */
    private boolean reconsentRequired;
    /** The version the worker agreed to; null = never (should not happen for a paired phone). */
    private Integer agreedVersion;
  }

  private String employeeName;
  private String shopName;
  /** Today's attendance day, yyyy-MM-dd, Asia/Kolkata. */
  private String today;
  /** The shop's "Biometric app check-in" switch as read for this answer (R1.3). */
  private boolean biometricApp;
  /** The server would accept a punch now (location aside: it is checked on the punch). */
  private boolean canPunch;
  /** Why not, in plain words; null when {@code canPunch}. */
  private String reason;
  /** IN when nobody has a counted punch today yet, else OUT (My HR rule, R4.1a). */
  private PunchDirection nextDirection;
  /** HH:mm of the first counted punch today; null = none. */
  private String todayIn;
  /** HH:mm of the last counted punch today when there are two or more; null = none. */
  private String todayOut;
  private AppLocationSettingsResponse location;
  private ConsentState consent;
}
