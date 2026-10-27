package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.HrNotificationType;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One inbox row for the caller; text has names and dates only. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HrNotificationResponse {
  private String id;
  private HrNotificationType type;
  private String leaveRequestId;
  private String text;
  private Instant createdAt;
  private boolean read;
}
