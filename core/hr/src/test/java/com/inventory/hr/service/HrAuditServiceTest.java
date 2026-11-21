// Feature: hr-module, Property 7: Without VIEW_SALARIES no response, log line or audit event carries pay amounts or full bank/UPI details
package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static com.inventory.hr.HrTestFixtures.employee;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.repository.HrAuditEventRepository;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Audit rows never carry bank account, IFSC, UPI or salary amounts (R14.7, design "Data model"),
 * only change flags. The {@code @Property} runs under {@code mvn test} because the class name ends
 * in {@code Test} (AGENTS.md T5).
 *
 * <p><b>Validates: Requirements 4.5, 4.13</b>
 */
@ExtendWith(MockitoExtension.class)
class HrAuditServiceTest {

  @Mock private HrAuditEventRepository repository;
  @Mock private MetricsWrapper metrics;
  @InjectMocks private HrAuditService service;

  private static AuditEntry employeeChange(Employee before, Employee after) {
    return new AuditEntry(SHOP, USER, HrAuditAction.EMPLOYEE_CHANGED, "EMPLOYEE", "e1", "e1",
        null, null, before != null ? EmployeeService.snapshot(before) : null,
        EmployeeService.snapshot(after), null);
  }

  @Test
  void bankAndSalaryChangesBecomeFlags() {
    Employee before = employee("e1");
    Employee after = employee("e1");
    after.setBankAccountNumber("999988887777");
    after.getSalaryRevisions().add(new Employee.SalaryRevision("2025-04", PayType.MONTHLY,
        new BigDecimal("16000"), Instant.EPOCH, USER));

    HrAuditEvent event = HrAuditService.toEvent(employeeChange(before, after), Instant.EPOCH);

    assertEquals(true, event.getAfter().get(HrAuditService.PAYMENT_DETAILS_CHANGED));
    assertEquals(true, event.getAfter().get(HrAuditService.SALARY_CHANGED));
    assertNoPii(event, before, after);
  }

  @Test
  void anUnchangedPayLeavesNoFlag() {
    Employee before = employee("e1");
    Employee after = employee("e1");
    after.setName("Ravi K");

    HrAuditEvent event = HrAuditService.toEvent(employeeChange(before, after), Instant.EPOCH);

    assertFalse(event.getAfter().containsKey(HrAuditService.PAYMENT_DETAILS_CHANGED));
    assertFalse(event.getAfter().containsKey(HrAuditService.SALARY_CHANGED));
    assertEquals("Ravi K", event.getAfter().get("name"));
  }

  @Test
  void nestedMapsAreStrippedToo() {
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("upiId", "ravi@okaxis");
    nested.put("label", "kept");
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("line", nested);

    Map<String, Object> stripped = HrAuditService.strip(after);

    assertEquals(Map.of("label", "kept"), stripped.get("line"));
  }

  @Test
  void theRowIsInsertedNeverUpdated() {
    service.record(employeeChange(null, employee("e1")));

    ArgumentCaptor<HrAuditEvent> saved = ArgumentCaptor.forClass(HrAuditEvent.class);
    verify(repository).insert(saved.capture());
    assertNull(saved.getValue().getBefore());
    assertEquals(true, saved.getValue().getAfter().get(HrAuditService.PAYMENT_DETAILS_CHANGED));
  }

  @Test
  void aFailedWriteIsCountedAndDoesNotFailTheAction() {
    when(repository.insert(any(HrAuditEvent.class))).thenThrow(new RuntimeException("down"));

    assertDoesNotThrow(() -> service.record(employeeChange(null, employee("e1"))));
    verify(metrics).increment(eq(HrMetricsConstants.AUDIT_FAILURES_TOTAL), anyDouble(),
        eq("module"), eq("hr"), eq("action"), eq("EMPLOYEE_CHANGED"));
  }

  @Property(tries = 100)
  void noAuditRowEverHoldsPaymentDetailsOrAmounts(
      @ForAll("employees") Employee before, @ForAll("employees") Employee after) {
    HrAuditEvent event = HrAuditService.toEvent(employeeChange(before, after), Instant.EPOCH);

    assertNoPii(event, before, after);
  }

  @Provide
  Arbitrary<Employee> employees() {
    Arbitrary<String> account = Arbitraries.strings().numeric().ofMinLength(6).ofMaxLength(18);
    Arbitrary<String> upi = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10)
        .map(s -> s + "@okaxis");
    Arbitrary<BigDecimal> amount = Arbitraries.bigDecimals()
        .between(new BigDecimal("1000.01"), new BigDecimal("999999.99")).ofScale(2);
    return Combinators.combine(account, upi, amount).as((a, u, m) -> {
      Employee e = employee("e1");
      e.setBankAccountNumber(a);
      e.setUpiId(u);
      e.getSalaryRevisions().get(0).setAmount(m);
      return e;
    });
  }

  private static void assertNoPii(HrAuditEvent event, Employee... employees) {
    String stored = String.valueOf(event.getBefore()) + event.getAfter() + event.getReason();
    for (Employee e : employees) {
      assertFalse(stored.contains(e.getBankAccountNumber()), "account number in audit");
      assertFalse(stored.contains(e.getIfsc()), "IFSC in audit");
      assertFalse(stored.contains(e.getUpiId()), "UPI id in audit");
      e.getSalaryRevisions().forEach(r ->
          assertFalse(stored.contains(r.getAmount().toPlainString()), "salary amount in audit"));
    }
    assertTrue(event.getAfter() == null || !event.getAfter().containsKey("salaryRevisions"));
  }
}
