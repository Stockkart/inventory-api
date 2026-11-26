package com.inventory.hr.rest.dto.request;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/hr/join-requests/bulk/approve} and {@code /bulk/reject} (worker-app R4.6):
 * the selected request ids, at most 50; {@code reason} is read by bulk reject only. Mirrored by
 * {@code BulkJoinRequest} in {@code attendance-protocol/src/worker.types.ts}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkJoinRequest {
  private List<String> ids;
  private String reason;
}
