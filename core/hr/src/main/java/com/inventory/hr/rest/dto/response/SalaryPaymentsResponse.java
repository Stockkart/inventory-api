package com.inventory.hr.rest.dto.response;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Result of one pay call: the batch, its journal and what happened to each requested line. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalaryPaymentsResponse {

  /** Line outcome: paid by this call, or why it was left alone. */
  public enum LineStatus { PAID, ALREADY_PAID, IN_PROGRESS, NOT_FOUND }

  /** ObjectId string shared by every line paid in this call; null when nothing was paid. */
  private String paymentBatchId;
  /** The one SALARY_PAYMENT journal of the batch; null when nothing was paid or the net is 0. */
  private String journalEntryId;
  private int paid;
  private List<Line> lines = new ArrayList<>();

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Line {
    private String lineId;
    private LineStatus status;
    /** Plain-English reason when not paid; null when paid. */
    private String error;
  }
}
