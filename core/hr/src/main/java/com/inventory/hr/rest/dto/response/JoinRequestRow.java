package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the manager's Join requests tab (worker-app R4.2, R11.3): the request snapshot, when
 * it was made, its status, and whether an employee of the shop looks like the same person. Nothing
 * else: no account id, no token, no pay. {@code
 * photoUrl} is set only when the account had a photo and is a Bearer-protected URL (R11.5).
 * Mirrored in {@code core/hr/src/model/join-request.types.ts} on the web.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JoinRequestRow {
  private String id;
  private String name;
  private String phone;
  private String email;
  private String photoUrl;
  private Instant requestedAt;
  private WorkerJoinStatus status;
  private boolean hasPossibleMatch;
}
