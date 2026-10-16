package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.enums.PrintBridgeState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The shop's print bridge compared with the release StockKart ships, and where to get it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintBridgeStatusResponse {
  private PrintBridgeState state;
  /** The version the bridge reported; null when none was reached. */
  private String installedVersion;
  private String selectedPrinter;
  private String latestVersion;
  private String minimumVersion;
  private String downloadUrl;
}
