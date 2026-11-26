package com.inventory.hr.rest.dto.response;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code GET /api/v1/hr/attendance-app/health}: the shop's open signature-failure alerts for the
 * HR dashboard panel (R10.5). Mirrors {@code AppHealthResponse} / {@code AppHealthAlert} in {@code
 * attendance-protocol}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppHealthResponse {

  /** Empty when the shop has no open alert. */
  private List<Alert> alerts;

  /** One open alert. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Alert {
    private String shopId;
    /** Signature failures divided by app punches in the 60 minutes that raised the alert. */
    private Double rate;
    private Integer failed;
    private Integer attempts;
    private Instant raisedAt;
  }
}
