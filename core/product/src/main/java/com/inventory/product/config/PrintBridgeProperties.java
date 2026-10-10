package com.inventory.product.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The print bridge StockKart ships, and how a print job is paced. Set per environment
 * ({@code PRINT_BRIDGE_*}), so a new bridge release needs no frontend deploy.
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

  /** How often the browser re-reads the bridge's job history while a job is queued. */
  private long pollIntervalMs = 500;

  /** How long the browser watches a job before reporting it still queued. */
  private long pollBudgetMs = 5000;

  /**
   * How long an unreported job blocks a second print of the same document. Past this the job
   * is marked EXPIRED (outcome unknown) and a new one may start, so a closed browser cannot
   * stop a document ever printing again.
   */
  private Duration inFlightTimeout = Duration.ofMinutes(2);
}
