package com.inventory.hr.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code GET /attendance/daily}: everyone employed on {@code date}, by name (R5.2). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyRegisterResponse {
  private String date;
  private boolean locked;
  private List<AttendanceRowResponse> rows;
}
