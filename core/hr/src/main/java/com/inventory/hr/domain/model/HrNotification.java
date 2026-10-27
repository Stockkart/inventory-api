package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.NotificationAudience;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** In-app HR inbox row (R6.9). Mongo removes rows 90 days after {@code createdAt} (TTL index). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_notifications")
public class HrNotification {

  @Id private String id;
  private String shopId;
  private NotificationAudience audience;
  /** null = APPROVERS row. */
  private String recipientUserId;
  private HrNotificationType type;
  private String leaveRequestId;
  /** Name and dates only; never pay data. */
  private String text;
  /** null = nobody read it. */
  private List<String> readByUserIds = new ArrayList<>();
  private Instant createdAt;
}
