package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.HrAuditAction;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Append-only HR audit row (R14.7); written only by {@code HrAuditService}, never updated or
 * deleted. {@code before}/{@code after} never hold bank account, IFSC, UPI or salary amounts; those
 * show as {@code "paymentDetailsChanged": true} / {@code "salaryChanged": true}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_audit_events")
public class HrAuditEvent {

  @Id private String id;
  private String shopId;
  private String actorUserId;
  private Instant at;
  private HrAuditAction action;
  private String entityType;
  private String entityId;
  /** History filters; null when the event is not about one employee, day or period. */
  private String employeeId;
  private String date;
  private String period;
  /** null = created. */
  private Map<String, Object> before;
  /** null = deleted. */
  private Map<String, Object> after;
  /** Reopen reason, leave note; null = none. */
  private String reason;
}
