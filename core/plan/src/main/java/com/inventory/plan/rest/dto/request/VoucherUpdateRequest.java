package com.inventory.plan.rest.dto.request;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What may change after issue. Type, value and add-on are fixed so past redemptions stay explainable. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherUpdateRequest {

  private Instant validTo;
  /** Cannot go below the slots already taken. */
  private Integer maxRedemptions;
  private String note;
}
