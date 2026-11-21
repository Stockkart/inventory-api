package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.api.MisDocuments;
import com.inventory.hr.domain.model.enums.DayStatus;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.repository.PayrollRunRepository;
import com.inventory.hr.rest.dto.response.MonthRegisterResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Register helpers and the R12.5 permission rule. */
@ExtendWith(MockitoExtension.class)
class HrReportServiceTest {

  @Mock private HrPermissionGuard guard;
  @Mock private PayrollRunRepository runRepository;
  @Mock private MisDocuments misDocuments;
  @InjectMocks private HrReportService service;

  @Test
  void registerLettersMatchTheScreenLegend() {
    assertEquals("P", HrReportService.letter(new MonthRegisterResponse.Cell(DayStatus.PRESENT,
        null, null, null)));
    assertEquals("P*", HrReportService.letter(new MonthRegisterResponse.Cell(DayStatus.PRESENT,
        true, null, null)));
    assertEquals("L/2", HrReportService.letter(new MonthRegisterResponse.Cell(DayStatus.LEAVE,
        null, true, DayStatus.PRESENT)));
    assertEquals("Ho", HrReportService.letter(new MonthRegisterResponse.Cell(DayStatus.HOLIDAY,
        null, null, null)));
    assertEquals("-", HrReportService.letter(new MonthRegisterResponse.Cell(DayStatus.UNMARKED,
        null, null, null)));
    // a day after today is not unmarked yet: blank
    assertEquals("", HrReportService.letter(new MonthRegisterResponse.Cell(DayStatus.UNMARKED,
        null, null, null, true)));
    assertEquals("", HrReportService.letter(new MonthRegisterResponse.Cell(
        DayStatus.NOT_EMPLOYED, null, null, null)));
  }

  @Test
  void formatIsXlsxByDefaultOrPdf() {
    assertFalse(HrReportService.parseFormat(null));
    assertFalse(HrReportService.parseFormat("XLSX"));
    assertTrue(HrReportService.parseFormat("pdf"));
    assertThrows(ValidationException.class, () -> HrReportService.parseFormat("csv"));
  }

  @Test
  void theSalaryRegisterNeedsViewSalaries() {
    when(guard.require("u1", "s1", HrPermission.VIEW_SALARIES))
        .thenThrow(new BaseException(ErrorCode.ACCESS_DENIED, "no"));

    BaseException e = assertThrows(BaseException.class,
        () -> service.salaryRegister("u1", "s1", "r1", "xlsx"));

    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    verify(runRepository, never()).findByShopIdAndId(any(), any());
    verify(misDocuments, never()).excel(any());
  }
}
