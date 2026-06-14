package com.inventory.reminders.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReminderExpiryBucketsResponse {
  private int expired;
  private int expiringWithin7Days;
  private int expiringWithinSoonDays;
  private int expiringSoonTotal;
  private int totalWithExpiry;
  private int expiringSoonDays;
}
