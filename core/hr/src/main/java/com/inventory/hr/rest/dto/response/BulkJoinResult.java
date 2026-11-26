package com.inventory.hr.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of "Approve selected" or "Reject selected" (worker-app R4.6, R14.4). The three lists
 * partition the selection: {@code approved} counts the requests decided (on a bulk reject, the
 * rejections; the field keeps one name so the web reads one shape), {@code skipped} the requests
 * left Pending for the approver to look at one by one, {@code failed} the requests whose own
 * decision was refused, each with the refusal text word for word.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkJoinResult {
  private int approved;
  private List<Skipped> skipped;
  private List<Failed> failed;

  /** Why a selected request was not decided in bulk. */
  public enum SkipReason {
    /** An employee of the shop looks like the same person; the approver chooses link or create. */
    POSSIBLE_MATCH
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Skipped {
    private String id;
    private SkipReason reason;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Failed {
    private String id;
    private String message;
  }
}
