package com.inventory.hr.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A worker's inbox read per account, not per shop (worker-app R10.1): the rows addressed to the
 * account across every shop, unread first then newest, and the total number still unread. Used by
 * {@code GET /hr/worker/notifications} and for the banner stack in the worker state.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HrAccountNotificationsResponse {
  private List<HrNotificationResponse> items;
  private long unread;
}
