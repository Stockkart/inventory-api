package com.inventory.hr.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/hr/join-requests/{id}/reject} (worker-app R4.7): an optional reason of at
 * most 200 characters after trimming; blank means none. The body itself may be absent. Mirrored by
 * {@code RejectJoinRequestRequest} in {@code attendance-protocol/src/worker.types.ts}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RejectJoinRequestRequest {
  private String reason;
}
