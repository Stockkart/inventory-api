package com.inventory.hr.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * {@code GET /api/v1/hr/worker/profile} and the answer to {@code PUT /api/v1/hr/worker/profile}
 * (worker-app R8.1, R8.2): the account's own details and, when the account is linked to an ACTIVE
 * employee, the shop name, employee code and join date. Mirrored field for field in {@code
 * attendance-protocol/src/worker.types.ts} as {@code WorkerProfileResponse}.
 *
 * <p>By construction this class has no salary, bank, UPI, payment-method or amount field (R11.2,
 * Property 18); the photo travels as a Bearer-protected URL (R11.5). The phone and e-mail stay out
 * of {@code toString} so a log line never carries them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerProfileResponse {
  private String name;
  /** 10 digits; null for a staff account created before the phone was required. */
  @ToString.Exclude private String phone;
  @ToString.Exclude private String email;
  /** {@code /api/v1/users/{userId}/photo}, fetched with the session; null when no photo. */
  private String photoUrl;
  /** ACTIVE only. */
  private String shopName;
  /** ACTIVE only. */
  private String employeeCode;
  /** ACTIVE only, yyyy-MM-dd. */
  private String joinDate;
  /** True when the shop has asked for profile details (R8.7). */
  private boolean profileRequested;
  /** Whether this worker's phone is paired (R7.1); {@code NOT_PAIRED} without an employee. */
  private PairingStateResponse.State pairingState;
}
