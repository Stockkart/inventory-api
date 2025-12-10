package com.inventory.notifications.domain.repository;

import com.inventory.notifications.domain.model.Event;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface EventRepository extends MongoRepository<Event, String> {
  List<Event> findByShopIdAndDeliveredFalseOrderByTriggeredAtAsc(String shopId);

  List<Event> findByDeliveredFalseAndRetryCountLessThanAndNextRetryAtLessThanEqualOrderByTriggeredAtAsc(
    int maxRetryCount, Instant now);

}
