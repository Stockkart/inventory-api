package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.rest.dto.hr.PayslipDocumentRequest;
import com.inventory.documentservice.service.hr.PayslipPdfService;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.enums.DeductionKind;
import com.inventory.hr.domain.model.enums.EarningKind;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.model.enums.SalaryPaymentStatus;
import com.inventory.hr.domain.repository.PayrollLineRepository;
import com.inventory.hr.rest.dto.request.EmailPayslipsRequest;
import com.inventory.user.service.ShopServiceAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/** Payslip content (R10.2) and the self-service / email guards (R10.4, R10.5). */
@ExtendWith(MockitoExtension.class)
class PayslipServiceTest {

  @Mock private PayrollLineRepository lineRepository;
  @Mock private SelfServiceResolver selfServiceResolver;
  @Mock private PayslipPdfService payslipPdfService;
  @Mock private HrPermissionGuard guard;
  @Spy private HrQueryRunner queryRunner = new HrQueryRunner();
  @InjectMocks private PayslipService service;

  static PayrollLine line() {
    PayrollLine l = new PayrollLine();
    l.setId("l1");
    l.setRunId("r1");
    l.setEmployeeId("e1");
    l.setPeriod("2025-04");
    l.setEmployeeSnapshot(new PayrollLine.EmployeeSnapshot("Asha", "EMP-001", "Helper",
        "2025-01-01", null, PayType.MONTHLY, new BigDecimal("14500"), 540));
    PayrollLine.DayCounts c = new PayrollLine.DayCounts();
    c.setEmployedDays(30);
    c.setPresent(new BigDecimal("24"));
    c.setHalfDays(0);
    c.setPaidLeave(BigDecimal.ZERO);
    c.setUnpaidLeave(BigDecimal.ZERO);
    c.setAbsent(new BigDecimal("2"));
    c.setWeekOff(new BigDecimal("4"));
    c.setHoliday(BigDecimal.ZERO);
    c.setOffDayWorked(BigDecimal.ZERO);
    c.setPaidDays(new BigDecimal("28.00"));
    c.setBasisDays(30);
    l.setDayCounts(c);
    l.setEarnings(new ArrayList<>(List.of(new PayrollLine.EarningItem(EarningKind.BASE,
        "Basic pay", new BigDecimal("13533.33")))));
    l.setDeductions(new ArrayList<>(List.of(new PayrollLine.DeductionItem(DeductionKind.ADVANCE,
        "Advance recovery", new BigDecimal("387.33")))));
    l.setGross(new BigDecimal("13533.33"));
    l.setTotalDeductions(new BigDecimal("387.33"));
    l.setNetBeforeRounding(new BigDecimal("13146.00"));
    l.setRounding(new BigDecimal("0.00"));
    l.setNet(new BigDecimal("13146"));
    l.setAdvanceOutstandingAfter(new BigDecimal("612.67"));
    l.setPaymentStatus(SalaryPaymentStatus.PAID);
    l.setPaidAt("2025-05-02");
    l.setPaymentMethod(HrPaymentMethod.UPI);
    return l;
  }

  @Test
  void thePayslipCarriesEverythingR10_2Asks() {
    PayslipDocumentRequest doc = PayslipService.toDocument(line(),
        new ShopServiceAdapter.ShopSummary("Shop", "1 Main Road"), Instant.EPOCH);

    assertEquals("Shop", doc.getShopName());
    assertEquals("1 Main Road", doc.getShopAddress());
    assertEquals("Asha", doc.getEmployeeName());
    assertEquals("EMP-001", doc.getEmployeeCode());
    assertEquals("Helper", doc.getDesignation());
    assertEquals("April 2025", doc.getPeriodLabel());
    assertEquals("Thirteen Thousands One Hundred and Forty Six Rupees only",
        doc.getNetPayInWords());
    assertEquals(0, doc.getAdvanceBalance().compareTo(new BigDecimal("612.67")));
    assertEquals("Paid on 2025-05-02 (UPI)", doc.getPaymentNote());
    assertEquals("Basic pay", doc.getEarnings().get(0).getLabel());
    assertEquals("Advance recovery", doc.getDeductions().get(0).getLabel());
    List<String> labels = doc.getDayRows().stream().map(PayslipDocumentRequest.Row::getLabel)
        .toList();
    assertEquals(List.of("Days employed", "Present", "Absent", "Week off", "Paid days"), labels);
    assertEquals("28 of 30", doc.getDayRows().get(4).getValue());
  }

  @Test
  void noAddressAndNoAdvanceAreLeftOut() {
    PayrollLine l = line();
    l.setAdvanceOutstandingAfter(BigDecimal.ZERO);
    l.setPaymentStatus(SalaryPaymentStatus.PENDING);

    PayslipDocumentRequest doc = PayslipService.toDocument(l, null, Instant.EPOCH);

    assertNull(doc.getShopAddress());
    assertNull(doc.getAdvanceBalance());
    assertNull(doc.getPaymentNote());
  }

  @Test
  void anotherEmployeesLineIsNotFoundThroughSelfService() {
    Employee self = new Employee();
    self.setId("e2");
    when(selfServiceResolver.resolve("s1", "u1")).thenReturn(self);
    when(lineRepository.findByShopIdAndId("s1", "l1")).thenReturn(Optional.of(line()));

    assertThrows(ResourceNotFoundException.class, () -> service.ownPayslip("s1", "u1", "l1"));
    verify(payslipPdfService, never()).generate(any());
  }

  @Test
  void emailNeedsBetweenOneAndThreeHundredLines() {
    EmailPayslipsRequest none = new EmailPayslipsRequest();
    EmailPayslipsRequest tooMany = new EmailPayslipsRequest();
    for (int i = 0; i < 301; i++) {
      tooMany.getLineIds().add("l" + i);
    }

    assertThrows(ValidationException.class, () -> service.email("u1", "s1", "r1", none));
    assertThrows(ValidationException.class, () -> service.email("u1", "s1", "r1", tooMany));
  }

  @Test
  void periodLabelsAreMonthNames() {
    assertEquals("December 2025", PayslipService.periodLabel("2025-12"));
    assertTrue(PayslipService.periodLabel("bad").contains("bad"));
  }
}
