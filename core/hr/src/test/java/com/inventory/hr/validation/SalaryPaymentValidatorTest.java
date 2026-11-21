package com.inventory.hr.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.rest.dto.request.RecordSalaryPaymentsRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SalaryPaymentValidatorTest {

  private static final LocalDate TODAY = LocalDate.of(2025, 5, 10);
  private final SalaryPaymentValidator validator = new SalaryPaymentValidator();

  private static RecordSalaryPaymentsRequest request(List<String> ids, Boolean all) {
    RecordSalaryPaymentsRequest r = new RecordSalaryPaymentsRequest();
    r.setLineIds(new ArrayList<>(ids));
    r.setAll(all);
    r.setDate("2025-05-02");
    r.setMethod("upi");
    r.setReference("  ref-1 ");
    return r;
  }

  @Test
  void acceptsDistinctIdsAndNormalisesMethodAndReference() {
    SalaryPaymentValidator.Checked c = validator.validate(request(List.of("a", "b", "a"), null),
        TODAY);

    assertEquals(List.of("a", "b"), c.lineIds());
    assertEquals(HrPaymentMethod.UPI, c.method());
    assertEquals("ref-1", c.reference());
    assertEquals(LocalDate.of(2025, 5, 2), c.date());
  }

  @Test
  void allMeansNoIds() {
    assertTrue(validator.validate(request(List.of(), true), TODAY).all());
    assertThrows(ValidationException.class,
        () -> validator.validate(request(List.of("a"), true), TODAY));
    assertThrows(ValidationException.class,
        () -> validator.validate(request(List.of(), false), TODAY));
  }

  @Test
  void atMostFiftyLines() {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 51; i++) {
      ids.add("l" + i);
    }
    assertThrows(ValidationException.class, () -> validator.validate(request(ids, null), TODAY));
  }

  @Test
  void dateMethodAndReferenceAreChecked() {
    RecordSalaryPaymentsRequest future = request(List.of("a"), null);
    future.setDate("2025-05-11");
    RecordSalaryPaymentsRequest badMethod = request(List.of("a"), null);
    badMethod.setMethod("CHEQUE");
    RecordSalaryPaymentsRequest longRef = request(List.of("a"), null);
    longRef.setReference("x".repeat(101));

    assertThrows(ValidationException.class, () -> validator.validate(future, TODAY));
    assertThrows(ValidationException.class, () -> validator.validate(badMethod, TODAY));
    assertThrows(ValidationException.class, () -> validator.validate(longRef, TODAY));
    assertThrows(ValidationException.class, () -> validator.validate(null, TODAY));
  }

  @Test
  void undoReasonIsOptionalAndBounded() {
    assertNull(validator.reason("  "));
    assertEquals("wrong", validator.reason(" wrong "));
    assertThrows(ValidationException.class, () -> validator.reason("x".repeat(501)));
  }
}
