package com.inventory.notifications.domain.repository;

import com.inventory.notifications.domain.model.Reminder;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ReminderRepository extends MongoRepository<Reminder, String> {

    List<Reminder> findByShopId(String shopId);
}

