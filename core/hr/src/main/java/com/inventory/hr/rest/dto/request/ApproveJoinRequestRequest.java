package com.inventory.hr.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/hr/join-requests/{id}/approve} (worker-app R4.4, R4.8). {@code mode} is
 * {@code CREATE} or {@code LINK} as text, parsed by {@code JoinRequestInboxValidator} so an unknown
 * value answers "mode must be CREATE or LINK" instead of a malformed-JSON error; {@code employeeId}
 * is required with {@code LINK} and ignored with {@code CREATE}. Mirrored by {@code
 * ApproveJoinRequestRequest} in {@code attendance-protocol/src/worker.types.ts}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApproveJoinRequestRequest {
  private String mode;
  private String employeeId;
}
