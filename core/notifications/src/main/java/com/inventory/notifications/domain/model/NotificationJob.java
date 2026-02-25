package com.inventory.notifications.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * MongoDB document representing a notification job.
 * Supports retry persistence and crash-safe recovery.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "notification_jobs")
@CompoundIndexes({
    @CompoundIndex(
        name = "retryable_jobs_idx",
        def = "{'status': 1, 'retryCount': 1, 'nextRetryAt': 1}"
    )
})
public class NotificationJob {

  @Id
  private String id;
  private NotificationType type;
  private NotificationChannel channel;
  private NotificationRecipient recipient;
  private NotificationPayload payload;
  @Builder.Default
  private NotificationStatus status = NotificationStatus.PENDING;
  @Builder.Default
  private int retryCount = 0;
  @Builder.Default
  private int maxRetries = 3;
  private Instant nextRetryAt;
  private String lastError;
  private Instant createdAt;
  private Instant updatedAt;

  public boolean isRetryable() {
    return status == NotificationStatus.FAILED
        && retryCount < maxRetries
        && (nextRetryAt == null || nextRetryAt.isBefore(Instant.now()) || nextRetryAt.equals(Instant.now()));
  }
}
