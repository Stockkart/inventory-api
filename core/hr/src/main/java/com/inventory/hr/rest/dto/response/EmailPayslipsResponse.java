package com.inventory.hr.rest.dto.response;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payslip emails queued (rendered and sent by the outbox over the next minutes) and the lines
 * left out because the employee has no email address. Email addresses are never returned.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailPayslipsResponse {
  private int queued;
  private List<Skipped> skippedNoEmail = new ArrayList<>();

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Skipped {
    private String lineId;
    private String employeeId;
    private String code;
    private String name;
  }
}
