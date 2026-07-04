package com.inventory.user.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Per-member module access flags (null = use role default on read). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemberModulePermissions {
  private Boolean accounting;
  private Boolean analytics;
  private Boolean taxes;
  private Boolean stockCorrection;
  private Boolean marketing;
  private Boolean paymentPlan;
  /** Explicit grant to edit products from product search / detail modal. */
  private Boolean productSearchEdit;
}
