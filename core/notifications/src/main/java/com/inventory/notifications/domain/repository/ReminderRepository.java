package com.inventory.notifications.domain.repository;

import com.inventory.notifications.domain.model.Reminder;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReminderRepository extends MongoRepository<Reminder, String> {

    List<Reminder> findByShopId(String shopId);
}

