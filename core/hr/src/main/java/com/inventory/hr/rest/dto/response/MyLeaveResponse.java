package com.inventory.hr.rest.dto.response;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code GET /me/leave}: the caller's balances for the leave year and own requests. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MyLeaveResponse {
  /** null when not employed in the leave year. */
  private LeaveBalanceRow balances;
  /** Newest first, at most 100. */
  private List<LeaveRequestResponse> requests = new ArrayList<>();
}
