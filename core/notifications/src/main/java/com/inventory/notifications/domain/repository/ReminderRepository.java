package com.inventory.notifications.domain.repository;

import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.model.ReminderStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

@Repository
public interface ReminderRepository extends MongoRepository<Reminder, String>, ReminderCustomRepository {
  List<Reminder> findByShopId(String shopId);

  Page<Reminder> findByShopIdOrderByReminderAtAsc(String shopId, Pageable pageable);

  List<Reminder> findTop100ByStatusAndReminderAtLessThanEqualOrderByReminderAtAsc(ReminderStatus status, Instant reminderAt);
}

