package com.inventory.hr.rest.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class HolidayResponse {
  private String id;
  /** yyyy-MM-dd. */
  private String date;
  private String name;
  private Integer year;
}
