package com.inventory.reminders.domain.repository;

import com.inventory.reminders.domain.model.Event;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface EventRepository extends MongoRepository<Event, String> {
  /** Undelivered events that belong to a reminder (the only ones replayed to a new stream). */
  List<Event> findByShopIdAndDeliveredFalseAndReminderIdNotNullOrderByTriggeredAtAsc(String shopId);

  List<Event> findByDeliveredFalseAndRetryCountLessThanAndNextRetryAtLessThanEqualOrderByTriggeredAtAsc(
    int maxRetryCount, Instant now);

}
