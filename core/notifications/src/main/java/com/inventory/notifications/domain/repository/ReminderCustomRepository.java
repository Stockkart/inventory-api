package com.inventory.notifications.domain.repository;

public interface ReminderCustomRepository {
  long deleteByIdReturningCount(String id);
}
