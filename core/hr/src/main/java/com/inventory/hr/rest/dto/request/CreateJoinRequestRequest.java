package com.inventory.hr.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /api/v1/hr/worker/join-requests} (worker-app R3.4): the shop the worker
 * confirmed with "Yes, this is my shop". Nothing else: the snapshot is taken from the account.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateJoinRequestRequest {
  private String shopId;
}
