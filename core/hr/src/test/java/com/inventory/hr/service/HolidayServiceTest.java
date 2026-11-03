package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.Holiday;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.repository.HolidayRepository;
import com.inventory.hr.mapper.HrSettingsMapper;
import com.inventory.hr.mapper.HrSettingsMapperImpl;
import com.inventory.hr.rest.dto.request.SaveHolidayRequest;
import com.inventory.hr.rest.dto.response.HolidayResponse;
import com.inventory.hr.validation.HolidayValidator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class HolidayServiceTest {

  @Mock private HolidayRepository holidayRepository;
  @Spy private HolidayValidator validator = new HolidayValidator();
  @Spy private HrSettingsMapper mapper = new HrSettingsMapperImpl();
  @Mock private HrPermissionGuard guard;
  @Mock private PeriodLockService periodLockService;
  @Mock private HrAuditService auditService;
  @InjectMocks private HolidayService service;

  private static SaveHolidayRequest request(String date, String name) {
    SaveHolidayRequest r = new SaveHolidayRequest();
    r.setDate(date);
    r.setName(name);
    return r;
  }

  private static Holiday holiday(String id, String date) {
    Holiday h = new Holiday();
    h.setId(id);
    h.setShopId(SHOP);
    h.setDate(date);
    h.setYear(Integer.parseInt(date.substring(0, 4)));
    h.setName("Diwali");
    return h;
  }

  @Test
  void creatingStoresTheYearAndChecksTheMonthLock() {
    when(holidayRepository.existsByShopIdAndDate(SHOP, "2025-10-20")).thenReturn(false);
    when(holidayRepository.save(any(Holiday.class))).thenAnswer(i -> i.getArgument(0));

    HolidayResponse response = service.create(USER, SHOP, request("2025-10-20", " Diwali "));

    assertEquals(2025, response.getYear());
    assertEquals("Diwali", response.getName());
    verify(guard).require(USER, SHOP, HrPermission.MANAGE_SETTINGS);
    verify(periodLockService).assertOpen(SHOP, "2025-10");
  }

  @Test
  void aDateIsLockedByTheSalaryMonthItFallsInUnderTheShopsStartDay() {
    // start day 26: 27 Sep 2026 is in salary month 2026-10 (26 Sep – 25 Oct), whose run is
    // finalised, so a holiday on it is refused although September itself has no run
    when(periodLockService.startDay(SHOP)).thenReturn(26);
    doThrow(new BaseException(ErrorCode.HR_PERIOD_LOCKED, "locked"))
        .when(periodLockService).assertOpen(SHOP, "2026-10");

    BaseException e = assertThrows(BaseException.class,
        () -> service.create(USER, SHOP, request("2026-09-27", "Navratri")));

    assertEquals(ErrorCode.HR_PERIOD_LOCKED, e.getErrorCode());
    verify(holidayRepository, never()).save(any());
  }

  @Test
  void aSecondHolidayOnTheSameDateIs409() {
    when(holidayRepository.existsByShopIdAndDate(SHOP, "2025-10-20")).thenReturn(true);

    assertThrows(ResourceExistsException.class,
        () -> service.create(USER, SHOP, request("2025-10-20", "Diwali")));
    verify(holidayRepository, never()).save(any());
  }

  @Test
  void aRaceOnTheUniqueIndexIsAlso409() {
    when(holidayRepository.existsByShopIdAndDate(SHOP, "2025-10-20")).thenReturn(false);
    when(holidayRepository.save(any(Holiday.class))).thenThrow(new DuplicateKeyException("dup"));

    assertThrows(ResourceExistsException.class,
        () -> service.create(USER, SHOP, request("2025-10-20", "Diwali")));
  }

  @Test
  void aBadDateIsRejected() {
    assertThrows(ValidationException.class,
        () -> service.create(USER, SHOP, request("2025-02-30", "Oops")));
  }

  @Test
  void anotherShopsHolidayIsNotFound() {
    when(holidayRepository.findByShopIdAndId(SHOP, "h-other")).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class,
        () -> service.update(USER, SHOP, "h-other", request("2025-10-21", "Diwali")));
    assertThrows(ResourceNotFoundException.class, () -> service.delete(USER, SHOP, "h-other"));
  }

  @Test
  void movingAHolidayChecksBothMonths() {
    when(holidayRepository.findByShopIdAndId(SHOP, "h1"))
        .thenReturn(Optional.of(holiday("h1", "2025-10-31")));
    when(holidayRepository.existsByShopIdAndDate(SHOP, "2025-11-01")).thenReturn(false);
    when(holidayRepository.save(any(Holiday.class))).thenAnswer(i -> i.getArgument(0));

    service.update(USER, SHOP, "h1", request("2025-11-01", "Diwali"));

    verify(periodLockService).assertOpen(SHOP, "2025-10");
    verify(periodLockService).assertOpen(SHOP, "2025-11");
  }

  @Test
  void deletingIsScopedByShop() {
    when(holidayRepository.findByShopIdAndId(SHOP, "h1"))
        .thenReturn(Optional.of(holiday("h1", "2025-10-20")));

    service.delete(USER, SHOP, "h1");

    verify(holidayRepository).deleteByShopIdAndId(SHOP, "h1");
  }

  @Test
  void aLockedMonthBlocksTheChange() {
    doThrow(new BaseException(ErrorCode.HR_PERIOD_LOCKED, "locked"))
        .when(periodLockService).assertOpen(SHOP, "2025-10");

    assertThrows(BaseException.class,
        () -> service.create(USER, SHOP, request("2025-10-20", "Diwali")));
    verify(holidayRepository, never()).save(any());
  }

  @Test
  void theListIsForTheRequestedYear() {
    when(holidayRepository.findByShopIdAndYearOrderByDateAsc(SHOP, 2025))
        .thenReturn(List.of(holiday("h1", "2025-01-26")));

    assertEquals(1, service.list(USER, SHOP, 2025).size());
    verify(guard).requireAny(USER, SHOP);
  }
}
