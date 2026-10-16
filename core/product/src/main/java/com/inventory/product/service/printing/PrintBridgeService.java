package com.inventory.product.service.printing;

import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.config.PrintBridgeProperties;
import com.inventory.product.domain.model.enums.PrintBridgeState;
import com.inventory.product.rest.dto.request.PrintBridgeObservation;
import com.inventory.product.rest.dto.response.PrintBridgeConnectionResponse;
import com.inventory.product.rest.dto.response.PrintBridgeStatusResponse;
import com.inventory.product.utils.BridgeVersion;
import com.inventory.product.utils.constants.ProductMetricsConstants;
import java.util.Locale;
import com.inventory.product.validation.PrintJobValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Decides what the shop's print bridge is - missing, outdated or connected - and where to get the
 * current one. The browser only reports what it saw; the comparison happens here, once.
 */
@Service
@RequiredArgsConstructor
public class PrintBridgeService {

  private final PrintBridgeProperties properties;
  private final PrintJobValidator printJobValidator;
  private final MetricsWrapper metrics;

  /** Where the browser reaches the bridge and how long it waits on it. Read before any probe. */
  public PrintBridgeConnectionResponse connection() {
    return new PrintBridgeConnectionResponse(
        properties.getOrigin(), properties.getHealthTimeoutMs(), properties.getPrintTimeoutMs());
  }

  public PrintBridgeStatusResponse status(PrintBridgeObservation observation) {
    printJobValidator.validateBridgeObservation(observation);
    PrintBridgeState state = stateFor(observation);
    // Counted here, where the print dialog asks, and not in stateFor: a print job asks too, and
    // counting both would report every print as two bridge checks.
    metrics.record(
        ProductMetricsConstants.PRINT_BRIDGE_CHECKS,
        1,
        "module",
        ProductMetricsConstants.MODULE,
        "outcome",
        state.name().toLowerCase(Locale.ROOT));
    return new PrintBridgeStatusResponse(
        state,
        observation.isReachable() ? observation.getVersion() : null,
        observation.isReachable() ? observation.getSelectedPrinter() : null,
        properties.getLatestVersion(),
        properties.getMinimumVersion(),
        properties.getDownloadUrl());
  }

  /**
   * An outdated bridge still prints - it just cannot do everything the current one does - so
   * OUTDATED is a hint to update, never a reason to stop printing.
   */
  public PrintBridgeState stateFor(PrintBridgeObservation observation) {
    if (observation == null || !observation.isReachable()) {
      return PrintBridgeState.NOT_DETECTED;
    }
    return BridgeVersion.isAtLeast(observation.getVersion(), properties.getMinimumVersion())
        ? PrintBridgeState.CONNECTED
        : PrintBridgeState.OUTDATED;
  }
}
