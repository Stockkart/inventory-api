package com.inventory.notifications.domain.repository;

import com.inventory.notifications.domain.model.MessageStatus;
import com.inventory.notifications.domain.model.OutboundMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;

public interface OutboundMessageRepository extends MongoRepository<OutboundMessage, String> {

  /**
   * Find PENDING messages ready for dispatch: either never attempted (nextRetryAt null)
   * or due for retry (nextRetryAt <= now), with retry count under max.
   */
  @Query("{ 'status' : ?0, 'retryCount' : { $lt : ?1 }, $or: [ { 'nextRetryAt' : null }, { 'nextRetryAt' : { $lte : ?2 } } ] }")
  List<OutboundMessage> findPendingForDispatch(MessageStatus status, int maxRetries, Instant now, Pageable pageable);
}
