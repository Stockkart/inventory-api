package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Where the browser reaches the print bridge on the shop PC, and how long it waits on it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintBridgeConnectionResponse {
  /** Loopback origin, e.g. {@code http://127.0.0.1:9110}. */
  private String origin;
  /** Timeout for {@code GET /health} and {@code GET /jobs}. */
  private long healthTimeoutMs;
  /** Timeout for {@code POST /print}. */
  private long printTimeoutMs;
}
