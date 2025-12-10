package com.inventory.notifications.domain.repository;

import com.inventory.notifications.domain.model.ReminderEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReminderEventRepository extends MongoRepository<ReminderEvent, String> {
    List<ReminderEvent> findByShopIdAndDeliveredFalseOrderByTriggeredAtAsc(String shopId);
}
