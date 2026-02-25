package com.inventory.reminders.domain.repository;

public interface ReminderCustomRepository {
  long deleteByIdReturningCount(String id);
}
