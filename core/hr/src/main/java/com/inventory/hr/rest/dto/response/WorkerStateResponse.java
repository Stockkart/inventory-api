package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * {@code GET /api/v1/hr/worker/me}: everything the Stockkart Team app needs to choose and
 * draw its screen (worker-app R6.1, R3.10, R5.5, R5.6, R6.4, R6.5, R10.1). The server decides
 * {@link #state} in R6.1 order; the optional sections are present exactly as their comments say,
 * and {@link #modules} is {@code WorkerModules.forShop} when ACTIVE and empty otherwise (R6.4).
 * Mirrored field for field in {@code attendance-protocol/src/worker.types.ts}.
 *
 * <p>Privacy (R8.9, R11.2): nothing here can carry a token, a proof, photo bytes, pay, bank or UPI
 * details; the photo travels as a Bearer-protected URL. The account's phone and e-mail stay out of
 * {@code toString} so a log line never carries them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerStateResponse {

  /** Where the account stands, decided by the server in R6.1 order. */
  public enum State {
    /** No shop, no linked ACTIVE employee, no open request: the Join screen (R3.1, R5.5). */
    NO_SHOP,
    /** One request waiting for a manager: the Waiting screen (R3.10). */
    PENDING,
    /** A linked ACTIVE employee, WORKER or staff: Home (R6.4, R5.6). */
    ACTIVE,
    /** A staff account with no linked employee anywhere: the Blocked screen (R5.6). */
    STAFF_NO_EMPLOYEE
  }

  private State state;
  private Account account;
  /** {@code PENDING} only: the one open request. */
  private JoinRequestSelfResponse request;
  /** {@code NO_SHOP} only: the last rejection or withdrawal within 90 days, if any (R4.12). */
  private LastOutcome lastOutcome;
  /** {@code ACTIVE} only. */
  private Shop shop;
  /** {@code ACTIVE} only. */
  private EmployeeSummary employee;
  /** Server-computed module keys in display order; {@code []} unless {@code ACTIVE} (R6.4, R6.6). */
  private List<String> modules;
  /** {@code ACTIVE} only (R7.1). */
  private Pairing pairing;
  /** {@code ACTIVE} only: the Attendance tile line (R6.5). */
  private Today today;
  /** The banner stack (R10.1). */
  private Notifications notifications;
  private Instant serverTime;

  /** The signed-in account as the app shows it. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Account {
    private String userId;
    private String name;
    @ToString.Exclude private String email;
    /** 10 digits; null for a staff account created before the phone was required. */
    @ToString.Exclude private String phone;
    /** {@code /api/v1/users/{userId}/photo}, fetched with the session; null when no photo. */
    private String photoUrl;
    /** The {@code UserRole} name. */
    private String role;
  }

  /** The last decided request within 90 days (R4.12). */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class LastOutcome {
    private String id;
    /** {@code REJECTED} or {@code WITHDRAWN}. */
    private WorkerJoinStatus status;
    private String shopName;
    /** The approver's reason, word for word; null when none was given (R4.7). */
    private String reason;
    private Instant decidedAt;
  }

  /** The shop the account is ACTIVE in. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Shop {
    private String shopId;
    private String shopName;
  }

  /** The linked employee, without pay or bank fields (R11.2). */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class EmployeeSummary {
    private String employeeId;
    private String code;
    private String name;
    /** yyyy-MM-dd. */
    private String joinDate;
    private EmployeeStatus status;
  }

  /** Whether this worker has a paired phone; never a key or installation id. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Pairing {
    private PairingStateResponse.State state;
    /** {@code PAIRED} only. */
    private String pairingId;
  }

  /** Today's attendance line (R6.5), from today's punches by the day-derivation rule. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Today {
    /** Today's attendance day, yyyy-MM-dd, Asia/Kolkata. */
    private String date;
    /** HH:mm of the first counted punch today; null = "Not checked in". */
    private String in;
    /** HH:mm of the last counted punch today when there are two or more. */
    private String out;
    private PunchDirection nextDirection;
  }

  /** The unread rows for the banner stack (R10.1). */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Notifications {
    private long unread;
    /** Newest unread first, at most 3. */
    private List<HrNotificationResponse> top;
  }
}
