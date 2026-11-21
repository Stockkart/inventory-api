package com.inventory.hr.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One page of leave requests (PG1–PG3). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaveRequestsPageResponse {
  private List<LeaveRequestResponse> items;
  private PageMeta page;
}
