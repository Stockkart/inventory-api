package com.inventory.product.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the browser saw when it probed the print bridge on this computer. Only a page on the shop
 * PC can reach the bridge, so the browser reports it; the backend decides what it means.
 *
 * <p>A compatibility signal, not a security boundary: nothing here stops a client misreporting.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintBridgeObservation {
  /** True when {@code GET /health} on the bridge answered. */
  private boolean reachable;
  /** The bridge's own {@code version}, as {@code /health} returned it. */
  private String version;
  /** The printer selected in the bridge window, for display only. */
  private String selectedPrinter;
}
