package com.inventory.notifications.domain.repository;

import com.inventory.notifications.domain.model.Reminder;
import com.mongodb.client.result.DeleteResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

public class ReminderCustomRepositoryImpl implements ReminderCustomRepository {
  @Autowired
  private MongoTemplate mongoTemplate;

  @Override
  public long deleteByIdReturningCount(String id) {
    DeleteResult result = mongoTemplate.remove(
            Query.query(Criteria.where("_id").is(id)),
            Reminder.class
    );
    return result.getDeletedCount();
  }
}
