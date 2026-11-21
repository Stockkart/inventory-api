package com.inventory.hr.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.enums.ManualItemKind;
import com.inventory.hr.rest.dto.request.SavePayrollAdjustmentsRequest;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PayrollAdjustmentValidatorTest {

  private final PayrollAdjustmentValidator validator = new PayrollAdjustmentValidator();

  private static SavePayrollAdjustmentsRequest request(String kind, String amount,
      String override) {
    return new SavePayrollAdjustmentsRequest(
        new ArrayList<>(List.of(new SavePayrollAdjustmentsRequest.Item(kind, " Diwali ",
            amount != null ? new BigDecimal(amount) : null))),
        override != null ? new BigDecimal(override) : null);
  }

  @Test
  void validItemsAreReturnedAsStored() {
    List<PayrollLine.ManualItem> items = validator.validate(request("bonus", "250.50", "0"));
    assertEquals(1, items.size());
    assertEquals(ManualItemKind.BONUS, items.get(0).getKind());
    assertEquals("Diwali", items.get(0).getNote());
    assertEquals(0, validator.validate(new SavePayrollAdjustmentsRequest(null, null)).size());
  }

  @Test
  void badItemsAndOverridesAreRefused() {
    assertThrows(ValidationException.class, () -> validator.validate(request("TIP", "1", null)));
    assertThrows(ValidationException.class, () -> validator.validate(request("BONUS", "0",
        null)));
    assertThrows(ValidationException.class, () -> validator.validate(request("BONUS", "1.005",
        null)));
    assertThrows(ValidationException.class, () -> validator.validate(request("BONUS", "1",
        "-1")));
    assertThrows(ValidationException.class, () -> validator.validate(null));
    SavePayrollAdjustmentsRequest many = new SavePayrollAdjustmentsRequest(new ArrayList<>(
        Collections.nCopies(21, new SavePayrollAdjustmentsRequest.Item("BONUS", null,
            BigDecimal.ONE))), null);
    assertThrows(ValidationException.class, () -> validator.validate(many));
  }

  @Test
  void netPayMayNotGoBelowZero() {
    PayrollLine line = new PayrollLine();
    line.setNetBeforeRounding(new BigDecimal("-0.01"));
    assertThrows(ValidationException.class, () -> validator.requireNonNegativeNet(line));
    line.setNetBeforeRounding(BigDecimal.ZERO);
    validator.requireNonNegativeNet(line);
  }

  @Test
  void periodsAreStrictAndNotInTheFuture() {
    YearMonth now = YearMonth.of(2025, 4);
    assertEquals(now, validator.requirePeriod("2025-04", now));
    assertThrows(ValidationException.class, () -> validator.requirePeriod("2025-05", now));
    assertThrows(ValidationException.class, () -> validator.requirePeriod("2025-4", now));
    assertThrows(ValidationException.class, () -> validator.requirePeriod(null, now));
  }

  @Test
  void reopenNeedsAReason() {
    assertThrows(ValidationException.class, () -> validator.requireReason(""));
    assertEquals("fix", validator.requireReason(" fix "));
  }
}
