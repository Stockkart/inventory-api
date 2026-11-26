package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.JoinOutcome;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A worker's request to join a shop (worker-app R3, R4; design DD2). Separate from the staff {@code
 * join_requests} owned by the user module: this one carries a snapshot, a rejection reason, an
 * employee outcome and a 90-day retention, and its approval creates or links an <em>employee</em>
 * (R4.8–R4.9).
 *
 * <p>One open request per account: a partial unique index on {@code userId} where {@code status in
 * [PENDING, APPROVING]} turns a race into a duplicate-key error (R3.5). {@link
 * WorkerJoinStatus#APPROVING} exists only inside the approval transaction (DD12); a sweep resets
 * any row left there for over 5 minutes by {@code updatedAt}.
 *
 * <p>Privacy (R3.9, R8.9, R11.3): the snapshot holds the account's name, phone, email and whether a
 * photo exists <em>as they were when the request was made</em>; it is never updated afterwards and
 * has no free-text message. The photo bytes are not copied ({@code hasPhoto} plus {@code userId}
 * is enough for the approver to view the account's current photo). Nothing here can hold a token,
 * an Aadhaar or PAN number, or an address. Phone and email are left out of {@code
 * toString} so a stray log line never carries them.
 *
 * <p>Retention (R4.12, R11.3): {@code expiresAt = decidedAt + 90 days}; a TTL index deletes the
 * row then. null while {@code PENDING}, so an open request never expires.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_join_requests")
public class WorkerJoinRequest {

  @Id private String id;
  private String shopId;
  /** The requesting account. */
  private String userId;
  private WorkerJoinStatus status;
  /** The account as it was at request time; frozen (R3.9). */
  private Snapshot snapshot;
  /** Server clock at creation (R3.4). */
  private Instant requestedAt;
  /** null while {@code PENDING}. */
  private Instant decidedAt;
  /** The approver, or the worker's own id on withdraw; null while {@code PENDING}. */
  private String decidedByUserId;
  /** null while {@code PENDING}. */
  private JoinOutcome outcome;
  /** The employee created or linked on approval; null otherwise. */
  private String employeeId;
  /** Trimmed, 1–200 characters; null = no reason given (R4.7). */
  private String rejectionReason;
  /** {@code decidedAt + 90 days}; TTL. null while {@code PENDING} (R4.12). */
  private Instant expiresAt;
  /** Last status change; the {@code APPROVING} sweep reads it (design "Data Models"). */
  private Instant updatedAt;

  /**
   * The account's contact details copied at request time (R3.9, R8.3). Approval with "Create new"
   * builds the employee from these, not from the account's current values.
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Snapshot {
    private String name;
    /** 10-digit Indian mobile, normalised; never verified (R1.3). */
    @ToString.Exclude private String phone;
    @ToString.Exclude private String email;
    /** Whether the account had a photo; the bytes stay in {@code user_photos} (R11.5). */
    private boolean hasPhoto;
  }
}
