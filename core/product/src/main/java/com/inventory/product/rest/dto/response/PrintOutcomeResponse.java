package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.enums.PrintJobStatus;
import com.inventory.product.domain.model.enums.PrintOutcome;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What a reported print means, and what the print screen does next. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintOutcomeResponse {
  private PrintOutcome outcome;
  private PrintJobStatus status;
  /** False when the operator must see the result to decide what to do, as on a printer fault. */
  private boolean shouldClose;
  /** True when trying again is safe and may help. */
  private boolean retryable;
  /** True when the browser should hand over the job's printer file instead. */
  private boolean downloadInstead;
  /** The printer's or the bridge's error text, when there was one. */
  private String error;
}
