// Feature: hr-module, Property 7: Without VIEW_SALARIES no response, log line or audit event carries pay amounts or full bank/UPI details
package com.inventory.hr.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.inventory.hr.HrTestFixtures;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.rest.dto.response.EmployeeResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 7 (design Property 13, Masking): for any employee, a response built for a caller
 * without VIEW_SALARIES — detail or list row — serialises to JSON with no salary amount, no full
 * account number and no full UPI id; list rows never carry the full account or UPI id even with
 * VIEW_SALARIES; and neither the entity's nor the response's {@code toString} (what a log line
 * would print) carries them.
 *
 * <p><b>Validates: Requirements 4.5, 4.13</b>
 */
class EmployeeMapperProperties {

  private final EmployeeMapper mapper = new EmployeeMapperImpl();
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

  @Property(tries = 100)
  void withoutViewSalariesNothingSensitiveIsSerialised(
      @ForAll("employees") Employee employee, @ForAll boolean listRow) throws Exception {
    EmployeeResponse response = listRow
        ? mapper.toListRow(employee, false)
        : mapper.toDetail(employee, false);
    String body = json.writeValueAsString(response);

    assertTrue(response.isPayHidden());
    assertNull(response.getSalaryAmount());
    assertNull(response.getSalaryRevisions());
    assertNull(response.getIfsc());
    assertNoSecrets(employee, body);
  }

  @Property(tries = 100)
  void listRowsNeverCarryFullBankOrUpiDetails(@ForAll("employees") Employee employee)
      throws Exception {
    String body = json.writeValueAsString(mapper.toListRow(employee, true));

    assertFalse(body.contains(employee.getBankAccountNumber()));
    assertFalse(body.contains(employee.getUpiId()));
  }

  @Property(tries = 100)
  void toStringNeverCarriesPiiOrPay(@ForAll("employees") Employee employee,
      @ForAll boolean canViewSalaries) {
    assertNoSecrets(employee, employee.toString());
    assertNoSecrets(employee, mapper.toDetail(employee, canViewSalaries).toString());
  }

  private static void assertNoSecrets(Employee employee, String text) {
    assertFalse(text.contains(employee.getBankAccountNumber()), "full account number");
    assertFalse(text.contains(employee.getUpiId()), "full UPI id");
    assertFalse(text.contains(employee.getIfsc()), "IFSC");
    for (Employee.SalaryRevision r : employee.getSalaryRevisions()) {
      assertFalse(text.contains(r.getAmount().toPlainString()), "salary amount");
    }
  }

  @Provide
  Arbitrary<Employee> employees() {
    // 9+ digits and amounts with a fractional part, so a value can never hide inside "XXXXXX" +
    // last 4, a code, a date or another number in the JSON
    Arbitrary<String> account = Arbitraries.strings().numeric().ofMinLength(9).ofMaxLength(18);
    Arbitrary<String> handle = Arbitraries.strings().withCharRange('a', 'z')
        .ofMinLength(3).ofMaxLength(12);
    Arbitrary<String> bank = Arbitraries.of("okaxis", "ybl", "paytm", "okhdfcbank");
    Arbitrary<List<BigDecimal>> amounts = Arbitraries.bigDecimals()
        .between(new BigDecimal("1000.01"), new BigDecimal("9999999.99")).ofScale(2)
        .filter(a -> a.remainder(BigDecimal.ONE).signum() != 0)
        .list().ofMinSize(1).ofMaxSize(3);
    return Combinators.combine(account, handle, bank, amounts).as((a, h, b, list) -> {
      Employee e = HrTestFixtures.employee("e1");
      e.setBankAccountNumber(a);
      e.setUpiId(h + "@" + b);
      List<Employee.SalaryRevision> revisions = new ArrayList<>();
      for (int i = 0; i < list.size(); i++) {
        revisions.add(new Employee.SalaryRevision("202" + i + "-04",
            i % 2 == 0 ? PayType.MONTHLY : PayType.DAILY, list.get(i), Instant.EPOCH, "u"));
      }
      e.setSalaryRevisions(revisions);
      return e;
    });
  }
}
