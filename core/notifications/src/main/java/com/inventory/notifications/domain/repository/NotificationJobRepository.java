package com.inventory.notifications.domain.repository;

import com.inventory.notifications.domain.model.NotificationJob;
import com.inventory.notifications.domain.model.NotificationStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;

/**
 * Repository for notification job persistence.
 */
public interface NotificationJobRepository extends MongoRepository<NotificationJob, String> {

  /**
   * Find failed jobs eligible for retry:
   * - status = FAILED
   * - retryCount &lt; maxRetryCount (use 10 as safe upper bound)
   * - nextRetryAt &lt;= now
   */
  @Query("{ 'status' : ?0, 'retryCount' : { $lt : ?1 }, 'nextRetryAt' : { $lte : ?2 } }")
  List<NotificationJob> findRetryableJobs(NotificationStatus status, int maxRetryCount, Instant now, Pageable pageable);

  default List<NotificationJob> findRetryableJobs(Instant now, int limit) {
    return findRetryableJobs(NotificationStatus.FAILED, 10, now, PageRequest.of(0, limit));
  }
}
