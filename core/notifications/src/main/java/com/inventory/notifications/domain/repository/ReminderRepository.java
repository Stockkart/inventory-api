package com.inventory.notifications.domain.repository;

import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.model.ReminderStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ReminderRepository extends MongoRepository<Reminder, String>, ReminderCustomRepository {
  List<Reminder> findByShopId(String shopId);

  List<Reminder> findTop100ByStatusAndReminderAtLessThanEqualOrderByReminderAtAsc(ReminderStatus status, Instant reminderAt);
}

