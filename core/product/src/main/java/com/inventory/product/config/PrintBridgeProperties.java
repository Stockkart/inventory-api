package com.inventory.product.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.regex.Pattern;
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

  /** Loopback only: the bridge is the printer on this PC, never a machine on the network. */
  private static final Pattern LOOPBACK_ORIGIN =
      Pattern.compile("^http://(127\\.0\\.0\\.1|localhost):\\d{1,5}$");

  /** Where the browser reaches the bridge on the shop PC. */
  private String origin = "http://127.0.0.1:9110";

  /**
   * How long the browser waits on the bridge's health and job history, so a probe never stalls
   * billing.
   */
  private long healthTimeoutMs = 1200;

  /** How long the browser waits for the bridge to accept a job: generous but bounded. */
  private long printTimeoutMs = 8000;

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

  /**
   * Refuses to start with an origin off this PC. The browser sends bills there, so a LAN or
   * internet address would hand shop data to another machine.
   */
  @PostConstruct
  public void requireLoopbackOrigin() {
    if (origin == null || !LOOPBACK_ORIGIN.matcher(origin).matches()) {
      throw new IllegalStateException(
          "print-bridge.origin must be http://127.0.0.1:<port> or http://localhost:<port>, was "
              + origin);
    }
  }
}
