package com.inventory.hr.rest.dto.request;

import lombok.Data;

@Data
public class ExitEmployeeRequest {
  /** yyyy-MM-dd, on or after the join date. */
  private String exitDate;
}
