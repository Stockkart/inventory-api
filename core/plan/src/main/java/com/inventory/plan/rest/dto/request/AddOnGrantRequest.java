package com.inventory.plan.rest.dto.request;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Platform-admin grant of an add-on outside an order, e.g. a goodwill gesture. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddOnGrantRequest {

  private String shopId;
  private String addOnCode;
  private Integer quantity;
  /** When an annual add-on ends; defaults to the shop's current term end. Ignored for OCR credits. */
  private Instant expiresAt;
  /** Required: who asked for it and why. */
  private String reason;
}
