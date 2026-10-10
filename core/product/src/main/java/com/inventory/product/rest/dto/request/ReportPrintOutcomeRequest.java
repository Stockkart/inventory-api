package com.inventory.product.rest.dto.request;

import com.inventory.product.domain.model.enums.PrintObservation;
import lombok.Data;

/** What happened when the browser sent a print job to the bridge. */
@Data
public class ReportPrintOutcomeRequest {
  private PrintObservation observation;
  /** The bridge's job id, once it accepted the job. */
  private String bridgeJobId;
  /** The printer's or the bridge's error text, as the bridge gave it. */
  private String error;
}
