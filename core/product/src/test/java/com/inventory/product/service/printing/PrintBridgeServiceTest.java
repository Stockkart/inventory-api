package com.inventory.product.service.printing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.config.PrintBridgeProperties;
import com.inventory.product.domain.model.enums.PrintBridgeState;
import com.inventory.product.rest.dto.request.PrintBridgeObservation;
import com.inventory.product.rest.dto.response.PrintBridgeStatusResponse;
import com.inventory.product.validation.PrintJobValidator;
import org.junit.jupiter.api.Test;

class PrintBridgeServiceTest {

  private final PrintBridgeProperties properties = properties();
  private final PrintBridgeService service =
      new PrintBridgeService(properties, new PrintJobValidator());

  @Test
  void noBridgeReachedIsNotDetectedAndOffersTheDownload() {
    PrintBridgeStatusResponse status =
        service.status(new PrintBridgeObservation(false, "0.12.0", "TVS"));

    assertEquals(PrintBridgeState.NOT_DETECTED, status.getState());
    assertNull(status.getInstalledVersion());
    assertNull(status.getSelectedPrinter());
    assertEquals("https://example.test/bridge.exe", status.getDownloadUrl());
    assertEquals("0.12.0", status.getLatestVersion());
  }

  @Test
  void aBridgeBelowTheMinimumIsOutdated() {
    assertEquals(
        PrintBridgeState.OUTDATED,
        service.stateFor(new PrintBridgeObservation(true, "0.10.0", "TVS")));
  }

  @Test
  void aBridgeAtTheMinimumIsConnected() {
    PrintBridgeStatusResponse status =
        service.status(new PrintBridgeObservation(true, "0.11.0", "TVS MSP 240 Star"));

    assertEquals(PrintBridgeState.CONNECTED, status.getState());
    assertEquals("0.11.0", status.getInstalledVersion());
    assertEquals("TVS MSP 240 Star", status.getSelectedPrinter());
  }

  @Test
  void anObservationIsRequired() {
    assertThrows(ValidationException.class, () -> service.status(null));
  }

  private static PrintBridgeProperties properties() {
    PrintBridgeProperties p = new PrintBridgeProperties();
    p.setLatestVersion("0.12.0");
    p.setMinimumVersion("0.11.0");
    p.setDownloadUrl("https://example.test/bridge.exe");
    return p;
  }
}
