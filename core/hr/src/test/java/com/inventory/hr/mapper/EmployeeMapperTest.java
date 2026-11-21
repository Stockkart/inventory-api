package com.inventory.hr.mapper;

import static com.inventory.hr.HrTestFixtures.employee;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.rest.dto.response.EmployeeResponse;
import com.inventory.hr.utils.EmployeeMaskingUtils;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EmployeeMapperTest {

  private final EmployeeMapper mapper = new EmployeeMapperImpl();

  @Test
  void withoutViewSalariesPayIsWithheldAndDetailsMasked() {
    EmployeeResponse r = mapper.toDetail(employee("e1"), false);

    assertTrue(r.isPayHidden());
    assertNull(r.getSalaryAmount());
    assertNull(r.getPayType());
    assertNull(r.getSalaryRevisions());
    assertNull(r.getPaymentMethod());
    assertNull(r.getIfsc());
    assertEquals("XXXXXX9012", r.getBankAccountNumber());
    assertEquals("ra***@okaxis", r.getUpiId());
    assertEquals("Ravi Kumar", r.getName());
  }

  @Test
  void withViewSalariesTheDetailViewShowsEverything() {
    EmployeeResponse r = mapper.toDetail(employee("e1"), true);

    assertFalse(r.isPayHidden());
    assertEquals(PayType.MONTHLY, r.getPayType());
    assertEquals(new BigDecimal("14500.00"), r.getSalaryAmount());
    assertEquals(1, r.getSalaryRevisions().size());
    assertEquals(HrPaymentMethod.BANK, r.getPaymentMethod());
    assertEquals("123456789012", r.getBankAccountNumber());
    assertEquals("HDFC0001234", r.getIfsc());
    assertEquals("ravi.kumar@okaxis", r.getUpiId());
  }

  @Test
  void listRowsAlwaysMaskBankAndUpi() {
    EmployeeResponse r = mapper.toListRow(employee("e1"), true);

    assertFalse(r.isPayHidden());
    assertEquals(new BigDecimal("14500.00"), r.getSalaryAmount());
    assertEquals("XXXXXX9012", r.getBankAccountNumber());
    assertEquals("ra***@okaxis", r.getUpiId());
  }

  @Test
  void theCurrentPayIsTheLatestRevisionInEffect() {
    Employee e = employee("e1");
    e.getSalaryRevisions().add(new Employee.SalaryRevision("9999-01", PayType.DAILY,
        new BigDecimal("700"), null, null));

    EmployeeResponse r = mapper.toDetail(e, true);

    assertEquals(PayType.MONTHLY, r.getPayType(), "a future revision is not in effect yet");
    assertEquals(2, r.getSalaryRevisions().size());
  }

  @Test
  void maskingEdgeCases() {
    assertNull(EmployeeMaskingUtils.maskAccount(null));
    assertEquals("XXXX", EmployeeMaskingUtils.maskAccount("1234"));
    assertNull(EmployeeMaskingUtils.maskUpi(" "));
    assertEquals("***", EmployeeMaskingUtils.maskUpi("noatsign"));
    assertEquals("a***@ybl", EmployeeMaskingUtils.maskUpi("ab@ybl"));
  }
}
