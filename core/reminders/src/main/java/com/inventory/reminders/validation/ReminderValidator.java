package com.inventory.reminders.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.reminders.domain.model.ReminderStatus;
import com.inventory.reminders.rest.dto.request.CreateReminderRequest;
import com.inventory.reminders.rest.dto.request.SnoozeReminderRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReminderValidator {

  public void validateCreateRequest(CreateReminderRequest request) {
    if (request == null) {
      throw new ValidationException("Create reminder request cannot be null");
    }
    if (!StringUtils.hasText(request.getShopId())) {
      throw new ValidationException("shopId is required");
    }
    if (request.getReminderAt() == null) {
      throw new ValidationException("reminderAt is required");
    }
    if (request.getEndDate() != null && !request.getEndDate().isAfter(request.getReminderAt())) {
      throw new ValidationException("endDate must be after reminderAt");
    }
  }

  public void validateSnoozeRequest(String id, SnoozeReminderRequest request) {
    if (!StringUtils.hasText(id)) {
      throw new ValidationException("Reminder ID is required");
    }
    if (request == null) {
      throw new ValidationException("Snooze reminder request cannot be null");
    }
    if (request.getSnoozeDays() == null || request.getSnoozeDays() <= 0) {
      throw new ValidationException("snoozeDays must be a positive number");
    }
  }

  public void validateShopId(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }
  }

  public void validateStatus(String id, String status) {
    if (!StringUtils.hasText(id)) {
      throw new ValidationException("Reminder ID is required");
    }
    if (status != null && !StringUtils.hasText(status)) {
      return;
    }
    if (status != null) {
      try {
        ReminderStatus.valueOf(status.toUpperCase());
      } catch (IllegalArgumentException ex) {
        throw new ValidationException("Invalid status value: " + status);
      }
    }
  }
}

