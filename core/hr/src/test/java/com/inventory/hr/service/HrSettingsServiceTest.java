package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.PerDayBasis;
import com.inventory.hr.domain.repository.AttendanceDayRepository;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.HrSettingsRepository;
import com.inventory.hr.mapper.HrSettingsMapper;
import com.inventory.hr.mapper.HrSettingsMapperImpl;
import com.inventory.hr.rest.dto.request.UpdateHrSettingsRequest;
import com.inventory.hr.rest.dto.response.HrSettingsResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.validation.HrSettingsValidator;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class HrSettingsServiceTest {

  @Mock private HrSettingsRepository settingsRepository;
  @Mock private EmployeeRepository employeeRepository;
  @Mock private AttendanceDayRepository attendanceDayRepository;
  @Spy private HrSettingsValidator validator = new HrSettingsValidator();
  @Spy private HrSettingsMapper mapper = new HrSettingsMapperImpl();
  @Mock private HrPermissionGuard guard;
  @Mock private PeriodLockService periodLockService;
  @Mock private HrAuditService auditService;
  @InjectMocks private HrSettingsService service;

  private static UpdateHrSettingsRequest.WeeklyOffChange weeklyOff(String from, DayOfWeek... days) {
    UpdateHrSettingsRequest.WeeklyOffChange change = new UpdateHrSettingsRequest.WeeklyOffChange();
    change.setEffectiveFrom(from);
    change.setDays(List.of(days));
    return change;
  }

  @Test
  void defaultsAreServedWithoutWritingAnything() {
    when(settingsRepository.findByShopId(SHOP)).thenReturn(Optional.empty());

    HrSettingsResponse response = service.get(USER, SHOP);

    assertFalse(response.isSaved());
    assertEquals("09:00", response.getWorkStart());
    assertEquals("21:00", response.getWorkEnd());
    assertEquals(15, response.getGraceMinutes());
    assertEquals(new BigDecimal("4"), response.getHalfDayBelowHours());
    assertEquals(false, response.getOvertimeEnabled());
    assertEquals(new BigDecimal("1.5"), response.getOvertimeMultiplier());
    assertEquals(PerDayBasis.CALENDAR_DAYS, response.getPerDayBasis());
    assertEquals(List.of(DayOfWeek.SUNDAY), response.getCurrentWeeklyOffDays());
    assertNull(response.getRevision());
    verify(settingsRepository, never()).save(any());
  }

  @Test
  void theResponseCarriesTheCurrentSalaryPeriod() {
    HrSettings stored = new HrSettings();
    stored.setPayCycleStartDay(24);
    when(settingsRepository.findByShopId(SHOP)).thenReturn(Optional.of(stored));
    try (org.mockito.MockedStatic<com.inventory.hr.utils.HrDateUtils> dates =
        org.mockito.Mockito.mockStatic(com.inventory.hr.utils.HrDateUtils.class,
            org.mockito.Mockito.CALLS_REAL_METHODS)) {
      dates.when(com.inventory.hr.utils.HrDateUtils::today)
          .thenReturn(java.time.LocalDate.of(2026, 10, 26));
      assertEquals("2026-11", service.get(USER, SHOP).getCurrentSalaryPeriod());
    }
  }

  @Test
  void readingNeedsSomeHrPermission() {
    doThrow(new BaseException(ErrorCode.ACCESS_DENIED, "no")).when(guard).requireAny(USER, SHOP);

    assertThrows(BaseException.class, () -> service.get(USER, SHOP));
  }

  @Test
  void savingNeedsManageSettings() {
    doThrow(new BaseException(ErrorCode.ACCESS_DENIED, "no"))
        .when(guard).require(USER, SHOP, HrPermission.MANAGE_SETTINGS);

    assertThrows(BaseException.class,
        () -> service.update(USER, SHOP, new UpdateHrSettingsRequest()));
    verify(settingsRepository, never()).save(any());
  }

  @Test
  void aWeeklyOffChangeAppliesFromItsMonthAndEarlierMonthsKeepTheOldDays() {
    when(settingsRepository.findByShopId(SHOP)).thenReturn(Optional.empty());
    when(settingsRepository.save(any(HrSettings.class))).thenAnswer(i -> i.getArgument(0));
    UpdateHrSettingsRequest request = new UpdateHrSettingsRequest();
    request.setWeeklyOff(weeklyOff("2025-04", DayOfWeek.TUESDAY));
    request.setGraceMinutes(10);

    HrSettingsResponse response = service.update(USER, SHOP, request);

    assertTrue(response.isSaved());
    assertEquals(10, response.getGraceMinutes());
    assertEquals(2, response.getWeeklyOffSchedule().size());
    ArgumentCaptor<HrSettings> saved = ArgumentCaptor.forClass(HrSettings.class);
    verify(settingsRepository).save(saved.capture());
    assertEquals(List.of(DayOfWeek.SUNDAY), HrSettingsService.weeklyOffFor(saved.getValue(), "2025-03"));
    assertEquals(List.of(DayOfWeek.TUESDAY), HrSettingsService.weeklyOffFor(saved.getValue(), "2025-04"));
    assertEquals(List.of(DayOfWeek.TUESDAY), HrSettingsService.weeklyOffFor(saved.getValue(), "2026-01"));
    verify(periodLockService).assertOpen(SHOP, "2025-04");
  }

  @Test
  void aNewRuleReplacesRulesStartingOnOrAfterIt() {
    List<HrSettings.WeeklyOffEntry> current = new ArrayList<>(List.of(
        new HrSettings.WeeklyOffEntry("0000-01", List.of(DayOfWeek.SUNDAY)),
        new HrSettings.WeeklyOffEntry("2025-06", List.of(DayOfWeek.MONDAY))));

    List<HrSettings.WeeklyOffEntry> next =
        HrSettingsService.applyWeeklyOff(current, "2025-05", List.of(DayOfWeek.FRIDAY));

    assertEquals(List.of("0000-01", "2025-05"),
        next.stream().map(HrSettings.WeeklyOffEntry::getEffectiveFrom).toList());
  }

  @Test
  void theSalaryMonthStartCannotChangeOnceAnyPayrollIsFinalised() {
    HrSettings stored = new HrSettings();
    stored.setShopId(SHOP);
    stored.setRevision(1L);
    when(settingsRepository.findByShopId(SHOP)).thenReturn(Optional.of(stored));
    when(periodLockService.hasLockedRun(SHOP)).thenReturn(true);
    UpdateHrSettingsRequest request = new UpdateHrSettingsRequest();
    request.setRevision(1L);
    request.setPayCycleStartDay(26);

    BaseException e = assertThrows(BaseException.class, () -> service.update(USER, SHOP, request));

    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, e.getErrorCode());
    assertEquals("The salary month start can be changed only before any payroll is finalised.",
        e.getMessage());
    verify(settingsRepository, never()).save(any());
  }

  @Test
  void theSalaryMonthStartChangesWhileOnlyDraftsExistAndDefaultsToOne() {
    when(settingsRepository.findByShopId(SHOP)).thenReturn(Optional.empty());
    when(settingsRepository.save(any(HrSettings.class))).thenAnswer(i -> i.getArgument(0));
    assertEquals(1, service.get(USER, SHOP).getPayCycleStartDay());
    UpdateHrSettingsRequest request = new UpdateHrSettingsRequest();
    request.setPayCycleStartDay(26);

    HrSettingsResponse response = service.update(USER, SHOP, request);

    assertEquals(26, response.getPayCycleStartDay());
    verify(periodLockService).hasLockedRun(SHOP);
  }

  @Test
  void sendingTheSameStartDayDoesNotNeedTheRunCheck() {
    HrSettings stored = new HrSettings();
    stored.setShopId(SHOP);
    stored.setRevision(1L);
    stored.setPayCycleStartDay(26);
    when(settingsRepository.findByShopId(SHOP)).thenReturn(Optional.of(stored));
    when(settingsRepository.save(any(HrSettings.class))).thenAnswer(i -> i.getArgument(0));
    UpdateHrSettingsRequest request = new UpdateHrSettingsRequest();
    request.setRevision(1L);
    request.setPayCycleStartDay(26);

    assertEquals(26, service.update(USER, SHOP, request).getPayCycleStartDay());
    verify(periodLockService, never()).hasLockedRun(SHOP);
  }

  @Test
  void aStaleRevisionIsAConflict() {
    HrSettings stored = new HrSettings();
    stored.setShopId(SHOP);
    stored.setRevision(3L);
    when(settingsRepository.findByShopId(SHOP)).thenReturn(Optional.of(stored));
    UpdateHrSettingsRequest request = new UpdateHrSettingsRequest();
    request.setRevision(2L);

    BaseException e = assertThrows(BaseException.class, () -> service.update(USER, SHOP, request));
    assertEquals(ErrorCode.HR_CONCURRENT_UPDATE, e.getErrorCode());
  }

  @Test
  void onceSavedAnUpdateMustSayWhichRevisionItEdits() {
    HrSettings stored = new HrSettings();
    stored.setShopId(SHOP);
    stored.setRevision(0L);
    when(settingsRepository.findByShopId(SHOP)).thenReturn(Optional.of(stored));

    assertThrows(ValidationException.class,
        () -> service.update(USER, SHOP, new UpdateHrSettingsRequest()));
    verify(settingsRepository, never()).save(any());
  }

  @Test
  void aLostUpdateAtSaveIsAConflict() {
    when(settingsRepository.findByShopId(SHOP)).thenReturn(Optional.empty());
    when(settingsRepository.save(any(HrSettings.class)))
        .thenThrow(new OptimisticLockingFailureException("version"));

    BaseException e = assertThrows(BaseException.class,
        () -> service.update(USER, SHOP, new UpdateHrSettingsRequest()));
    assertEquals(ErrorCode.HR_CONCURRENT_UPDATE, e.getErrorCode());
  }

  @Test
  void hoursAreCheckedAfterMergingWithTheStoredRow() {
    when(settingsRepository.findByShopId(SHOP)).thenReturn(Optional.empty());
    UpdateHrSettingsRequest request = new UpdateHrSettingsRequest();
    request.setWorkStart("22:00"); // after the default end 21:00

    assertThrows(ValidationException.class, () -> service.update(USER, SHOP, request));
  }

  @Test
  void aSaveIsAuditedAsSettingsChanged() {
    when(settingsRepository.findByShopId(SHOP)).thenReturn(Optional.empty());
    when(settingsRepository.save(any(HrSettings.class))).thenAnswer(i -> i.getArgument(0));
    UpdateHrSettingsRequest request = new UpdateHrSettingsRequest();
    request.setOvertimeEnabled(true);

    service.update(USER, SHOP, request);

    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(entry.capture());
    assertEquals(HrAuditAction.SETTINGS_CHANGED, entry.getValue().action());
    assertNull(entry.getValue().before());
    assertEquals(true, entry.getValue().after().get("overtimeEnabled"));
  }

  @Test
  void setupStatusReportsWhatExists() {
    when(settingsRepository.existsByShopId(SHOP)).thenReturn(true);
    when(employeeRepository.countByShopIdAndStatus(SHOP, EmployeeStatus.ACTIVE)).thenReturn(4L);
    when(attendanceDayRepository.existsByShopId(SHOP)).thenReturn(false);

    var status = service.setupStatus(USER, SHOP);

    assertTrue(status.isSettingsSaved());
    assertEquals(4L, status.getEmployeeCount());
    assertFalse(status.isAttendanceMarked());
  }
}
