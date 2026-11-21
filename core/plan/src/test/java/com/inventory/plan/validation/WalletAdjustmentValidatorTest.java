package com.inventory.plan.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.rest.dto.request.WalletAdjustmentRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WalletAdjustmentValidatorTest {

  private final WalletAdjustmentValidator validator = new WalletAdjustmentValidator();

  private static WalletAdjustmentRequest request(String amount) {
    WalletAdjustmentRequest request = new WalletAdjustmentRequest();
    request.setAmount(new BigDecimal(amount));
    request.setReason("Correction");
    return request;
  }

  @Test
  void theCapMessageShowsRupeesAsPeopleReadThem() {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.validateAdjustment(request("-10000.01")));

    assertEquals("A single adjustment can be at most ₹10,000 either way", e.getMessage());
  }

  @Test
  void anAmountThatRoundsToZeroNamesTheSmallestAdjustment() {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.validatePreview(request("0.001")));

    assertEquals("Adjustment amount must be at least ₹0.01 either way", e.getMessage());
  }

  @Test
  void theCapItselfIsAllowed() {
    assertEquals(new BigDecimal("10000.00"), validator.validateAdjustment(request("10000")));
  }
}
