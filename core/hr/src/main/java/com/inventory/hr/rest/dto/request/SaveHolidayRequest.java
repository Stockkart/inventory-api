package com.inventory.hr.rest.dto.request;

import lombok.Data;

@Data
public class SaveHolidayRequest {
  /** yyyy-MM-dd. */
  private String date;
  private String name;
}
