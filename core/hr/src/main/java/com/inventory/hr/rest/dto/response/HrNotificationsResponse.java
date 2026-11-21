package com.inventory.hr.rest.dto.response;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A poll of the caller's inbox. {@code cursor} is the newest {@code createdAt} the query looked
 * at (including approver rows dropped for a caller without APPROVE_LEAVE); send it back as {@code
 * since} on the next poll. null = nothing seen yet.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HrNotificationsResponse {
  private List<HrNotificationResponse> items;
  private Instant cursor;
}
