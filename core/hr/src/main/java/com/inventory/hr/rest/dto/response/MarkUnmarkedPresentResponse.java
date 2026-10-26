package com.inventory.hr.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** How many unmarked employee-days were marked Present. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarkUnmarkedPresentResponse {
  private int marked;
}
