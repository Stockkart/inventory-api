package com.inventory.hr.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.rest.dto.request.AddSalaryRevisionRequest;
import com.inventory.hr.rest.dto.request.SaveEmployeeRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmployeeValidatorTest {

  private final EmployeeValidator validator = new EmployeeValidator();

  private static SaveEmployeeRequest valid() {
    SaveEmployeeRequest r = new SaveEmployeeRequest();
    r.setName("Ravi");
    r.setCode("EMP-001");
    r.setJoinDate("2025-04-01");
    return r;
  }

  @ParameterizedTest
  @ValueSource(strings = {"HDFC0001234", "SBIN0ABC123", "ICIC0000001"})
  void validIfscCodesMatch(String ifsc) {
    assertTrue(EmployeeValidator.IFSC.matcher(ifsc).matches());
  }

  @ParameterizedTest
  @ValueSource(strings = {"HDFC1001234", "HDF0001234", "hdfc0001234", "HDFC00012345", "1234056789A"})
  void invalidIfscCodesDoNotMatch(String ifsc) {
    assertFalse(EmployeeValidator.IFSC.matcher(ifsc).matches());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ravi@okaxis", "ravi.kumar-1@ybl", "r_9@paytm"})
  void validUpiIdsMatch(String upi) {
    assertTrue(EmployeeValidator.UPI.matcher(upi).matches());
  }

  @ParameterizedTest
  @ValueSource(strings = {"r@okaxis", "ravi@", "ravi@o", "ravi@ok1", "ravi okaxis", "@okaxis"})
  void invalidUpiIdsDoNotMatch(String upi) {
    assertFalse(EmployeeValidator.UPI.matcher(upi).matches());
  }

  @Test
  void aMinimalEmployeeIsValid() {
    assertDoesNotThrow(() -> validator.validate(valid(), true));
  }

  @Test
  void nameIsRequiredAndCodeOnUpdate() {
    SaveEmployeeRequest noName = valid();
    noName.setName(" ");
    assertThrows(ValidationException.class, () -> validator.validate(noName, true));

    SaveEmployeeRequest noCode = valid();
    noCode.setCode(null);
    assertDoesNotThrow(() -> validator.validate(noCode, true));
    assertThrows(ValidationException.class, () -> validator.validate(noCode, false));
  }

  @Test
  void bankPaymentNeedsAValidAccountAndIfsc() {
    SaveEmployeeRequest r = valid();
    r.setPaymentMethod(HrPaymentMethod.BANK);
    r.setBankAccountNumber("123456789012");
    assertThrows(ValidationException.class, () -> validator.validate(r, true));
    r.setIfsc("hdfc0001234"); // lower case is upper-cased before the check
    assertDoesNotThrow(() -> validator.validate(r, true));
    r.setBankAccountNumber("12AB");
    ValidationException e =
        assertThrows(ValidationException.class, () -> validator.validate(r, true));
    assertFalse(e.getMessage().contains("12AB"), "messages never echo the account number");
  }

  @Test
  void upiPaymentNeedsAValidUpiId() {
    SaveEmployeeRequest r = valid();
    r.setPaymentMethod(HrPaymentMethod.UPI);
    assertThrows(ValidationException.class, () -> validator.validate(r, true));
    r.setUpiId("ravi@okaxis");
    assertDoesNotThrow(() -> validator.validate(r, true));
  }

  @Test
  void payOnCreateNeedsATypeAndAPositiveAmountInPaise() {
    SaveEmployeeRequest r = valid();
    r.setSalaryAmount(new BigDecimal("14500"));
    assertThrows(ValidationException.class, () -> validator.validate(r, true));
    r.setPayType(PayType.MONTHLY);
    assertDoesNotThrow(() -> validator.validate(r, true));
    r.setSalaryAmount(new BigDecimal("100.005"));
    assertThrows(ValidationException.class, () -> validator.validate(r, true));
    r.setSalaryAmount(BigDecimal.ZERO);
    assertThrows(ValidationException.class, () -> validator.validate(r, true));
  }

  @Test
  void ownHoursNeedBothEndsInOrder() {
    SaveEmployeeRequest r = valid();
    r.setWorkStart("10:00");
    assertThrows(ValidationException.class, () -> validator.validate(r, true));
    r.setWorkEnd("09:00");
    assertThrows(ValidationException.class, () -> validator.validate(r, true));
    r.setWorkEnd("18:00");
    assertDoesNotThrow(() -> validator.validate(r, true));
  }

  @Test
  void exitDateIsRequiredAndNotBeforeJoining() {
    assertThrows(ValidationException.class, () -> validator.validateExit(null, "2025-04-01"));
    assertThrows(ValidationException.class,
        () -> validator.validateExit("2025-03-31", "2025-04-01"));
    assertDoesNotThrow(() -> validator.validateExit("2025-04-01", "2025-04-01"));
    assertDoesNotThrow(() -> validator.validateExit("2025-04-01", null));
  }

  @Test
  void aRevisionNeedsAMonth() {
    AddSalaryRevisionRequest r = new AddSalaryRevisionRequest();
    r.setEffectiveFrom("2025-4");
    r.setPayType(PayType.DAILY);
    r.setAmount(new BigDecimal("600"));
    assertThrows(ValidationException.class, () -> validator.validateRevision(r));
    r.setEffectiveFrom("2025-04");
    assertDoesNotThrow(() -> validator.validateRevision(r));
  }
}
