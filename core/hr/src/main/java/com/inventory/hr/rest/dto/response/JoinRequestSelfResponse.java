package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A worker's own join request as the app sees it (worker-app R3.4, R3.6): the shop's name and
 * where the request stands, nothing about the approver. Mirrored in {@code
 * attendance-protocol/src/worker.types.ts}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JoinRequestSelfResponse {
  private String id;
  private String shopName;
  private WorkerJoinStatus status;
  private Instant requestedAt;
}
