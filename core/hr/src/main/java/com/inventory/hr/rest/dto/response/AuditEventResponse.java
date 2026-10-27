package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.HrAuditAction;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One audit row as shown in history; payloads are already stripped of PII and pay. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditEventResponse {
  private HrAuditAction action;
  private String actorUserId;
  /** Display name of the actor; null = unknown login. */
  private String actorName;
  private Instant at;
  private Map<String, Object> before;
  private Map<String, Object> after;
  private String reason;
}
