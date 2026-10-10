package com.inventory.product.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The print bridge StockKart ships. Set per environment ({@code PRINT_BRIDGE_*}), so a new
 * bridge release needs no frontend deploy.
 */
@Data
@Component
@ConfigurationProperties(prefix = "print-bridge")
public class PrintBridgeProperties {

  /** The current release, offered to shops that have none or an older one. */
  private String latestVersion = "0.12.0";

  /**
   * The oldest release treated as up to date. 0.11.0 is the first that reads {@code docType}
   * and gives an estimate its own, shorter page; anything older prints estimates at bill length.
   */
  private String minimumVersion = "0.11.0";

  /** Where the shop downloads the bridge. */
  private String downloadUrl;
}
