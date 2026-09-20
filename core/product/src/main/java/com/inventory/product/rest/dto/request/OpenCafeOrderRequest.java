package com.inventory.product.rest.dto.request;

import lombok.Data;

@Data
public class OpenCafeOrderRequest {

  /** DINE_IN or TAKEAWAY. */
  private String orderType;

  /** Required for DINE_IN. */
  private String tableLabel;
}
