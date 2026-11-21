package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code GET /attendance/history} (R5.15): who marked the day first (kept on the day itself) and
 * every later change, newest first.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceHistoryResponse {
  private String employeeId;
  private String date;
  /** Current stored mark; null = nothing stored. */
  private AttendanceStatus status;
  private AttendanceSource source;
  private Instant markedAt;
  private String markedByUserId;
  /** Display name of {@code markedByUserId} (the first marker); null = unknown login. */
  private String actorName;
  private List<AuditEventResponse> events;
}
