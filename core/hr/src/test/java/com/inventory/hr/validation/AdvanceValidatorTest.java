package com.inventory.hr.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.rest.dto.request.RecordAdvanceRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AdvanceValidatorTest {

  private static final LocalDate TODAY = LocalDate.of(2025, 4, 30);
  private final AdvanceValidator validator = new AdvanceValidator();

  private static RecordAdvanceRequest request(String date, String amount, String monthly,
      String paidBy) {
    return new RecordAdvanceRequest("e1", date, amount != null ? new BigDecimal(amount) : null,
        monthly != null ? new BigDecimal(monthly) : null, paidBy, null);
  }

  @Test
  void aValidAdvanceReturnsItsPaymentMethod() {
    assertEquals(HrPaymentMethod.UPI,
        validator.validate(request("2025-04-30", "3000", "1000", "upi"), TODAY));
    assertEquals(HrPaymentMethod.CASH,
        validator.validate(request("2025-04-01", "1000.50", "1000.50", "CASH"), TODAY));
  }

  @Test
  void amountsMustBePositiveWithPaiseAtMost() {
    assertThrows(ValidationException.class,
        () -> validator.validate(request("2025-04-01", "0", "1", "CASH"), TODAY));
    assertThrows(ValidationException.class,
        () -> validator.validate(request("2025-04-01", "10.001", "1", "CASH"), TODAY));
    assertThrows(ValidationException.class,
        () -> validator.validate(request("2025-04-01", null, "1", "CASH"), TODAY));
    assertThrows(ValidationException.class,
        () -> validator.validate(request("2025-04-01", "100000000", "1", "CASH"), TODAY));
  }

  @Test
  void theMonthlyDeductionIsMoreThanZeroAndAtMostTheAmount() {
    assertThrows(ValidationException.class,
        () -> validator.validate(request("2025-04-01", "1000", "0", "CASH"), TODAY));
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.validate(request("2025-04-01", "1000", "1000.01", "CASH"), TODAY));
    assertFalse(e.getMessage().contains("1000"), "no amounts in messages");
  }

  @Test
  void paidByAndDateAreChecked() {
    assertThrows(ValidationException.class,
        () -> validator.validate(request("2025-04-01", "10", "1", "CHEQUE"), TODAY));
    assertThrows(ValidationException.class,
        () -> validator.validate(request("2025-04-01", "10", "1", null), TODAY));
    assertThrows(ValidationException.class,
        () -> validator.validate(request("2025-05-01", "10", "1", "CASH"), TODAY));
    assertThrows(ValidationException.class,
        () -> validator.validate(request("2025-02-30", "10", "1", "CASH"), TODAY));
    assertThrows(ValidationException.class, () -> validator.validate(
        new RecordAdvanceRequest(null, "2025-04-01", BigDecimal.TEN, BigDecimal.ONE, "CASH",
            null), TODAY));
  }

  @Test
  void cancelNeedsAReason() {
    assertThrows(ValidationException.class, () -> validator.requireReason(" "));
    assertEquals("returned", validator.requireReason(" returned "));
  }
}
