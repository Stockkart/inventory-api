package com.inventory.hr.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One page of employees (PG1–PG3); rows are always masked. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeesPageResponse {
  private List<EmployeeResponse> items;
  private PageMeta page;
}
