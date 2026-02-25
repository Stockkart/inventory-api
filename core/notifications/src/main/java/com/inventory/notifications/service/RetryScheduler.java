package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.NotificationJob;
import com.inventory.notifications.domain.repository.NotificationJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Periodically retries failed notification jobs with backoff.
 */
@Slf4j
@Component
public class RetryScheduler {

  @Value("${notification.retry.interval-ms:60000}")
  private long retryIntervalMs;

  @Value("${notification.retry.batch-size:50}")
  private int batchSize;

  private final NotificationJobRepository notificationJobRepository;
  private final NotificationService notificationService;

  public RetryScheduler(NotificationJobRepository notificationJobRepository,
      NotificationService notificationService) {
    this.notificationJobRepository = notificationJobRepository;
    this.notificationService = notificationService;
  }

  @Scheduled(fixedDelayString = "${notification.retry.interval-ms:60000}")
  public void retryFailedNotifications() {
    Instant now = Instant.now();
    List<NotificationJob> jobs = notificationJobRepository.findRetryableJobs(now, batchSize);

    if (jobs.isEmpty()) {
      return;
    }

    log.info("Retrying {} failed notification job(s)", jobs.size());
    for (NotificationJob job : jobs) {
      if (!job.isRetryable()) {
        continue;
      }
      try {
        notificationService.sendNotificationAsync(job);
      } catch (Exception e) {
        log.warn("Retry failed for jobId={}: {}", job.getId(), e.getMessage());
      }
    }
  }
}
