package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.LeaveYearStartMonth;
import com.inventory.hr.domain.model.enums.PerDayBasis;
import com.inventory.hr.domain.repository.AttendanceDayRepository;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.HrSettingsRepository;
import com.inventory.hr.mapper.HrSettingsMapper;
import com.inventory.hr.rest.dto.request.UpdateHrSettingsRequest;
import com.inventory.hr.rest.dto.response.HrSettingsResponse;
import com.inventory.hr.rest.dto.response.HrSetupStatusResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.PayCycle;
import com.inventory.hr.validation.HrSettingsValidator;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * HR settings of a shop (R2). Reads serve the defaults without writing anything, so opening the
 * HR tab never creates a document; the first PUT stores it.
 */
@Slf4j
@Service
public class HrSettingsService {

  /** Weekly off before any change: Sunday from the start of time. */
  static final String SCHEDULE_START = "0000-01";
  static final String DEFAULT_WORK_START = "09:00";
  static final String DEFAULT_WORK_END = "21:00";
  static final int DEFAULT_GRACE_MINUTES = 15;
  static final BigDecimal DEFAULT_HALF_DAY_BELOW_HOURS = new BigDecimal("4");
  static final BigDecimal DEFAULT_OVERTIME_MULTIPLIER = new BigDecimal("1.5");
  static final String PAY_CYCLE_LOCKED_MESSAGE =
      "The salary month start can be changed only before any payroll is finalised.";

  @Autowired private HrSettingsRepository settingsRepository;

  @Autowired private EmployeeRepository employeeRepository;

  @Autowired private AttendanceDayRepository attendanceDayRepository;

  @Autowired private HrSettingsValidator validator;

  @Autowired private HrSettingsMapper mapper;

  @Autowired private HrPermissionGuard guard;

  @Autowired private PeriodLockService periodLockService;

  @Autowired private HrAuditService auditService;

  /** Any HR permission may read the settings (R2.1; the tab is read-only without MANAGE_SETTINGS). */
  public HrSettingsResponse get(String userId, String shopId) {
    guard.requireAny(userId, shopId);
    HrSettings stored = settingsRepository.findByShopId(shopId).orElse(null);
    return toResponse(stored);
  }

  /** Settings with defaults for the services that need them (attendance, payroll). No guard. */
  public HrSettings effectiveSettings(String shopId) {
    return withDefaults(settingsRepository.findByShopId(shopId).orElse(null), shopId);
  }

  public HrSettingsResponse update(String userId, String shopId, UpdateHrSettingsRequest request) {
    guard.require(userId, shopId, HrPermission.MANAGE_SETTINGS);
    validator.validate(request);
    HrSettings stored = settingsRepository.findByShopId(shopId).orElse(null);
    if (stored != null && request.getRevision() == null) {
      throw new ValidationException("revision is required: send the revision you last read");
    }
    if (request.getRevision() != null
        && !request.getRevision().equals(stored != null ? stored.getRevision() : null)) {
      throw concurrentUpdate();
    }
    HrSettings target = stored != null ? stored : newSettings(shopId);
    if (stored != null) {
      // the client's revision goes onto the entity, so @Version also catches a save that lands
      // between this read and ours
      target.setRevision(request.getRevision());
    }
    Map<String, Object> before = stored != null ? snapshot(withDefaults(stored, shopId)) : null;

    if (request.getWeeklyOff() != null) {
      String from = request.getWeeklyOff().getEffectiveFrom();
      // a new weekly-off rule may not start in a month whose payroll is finalised
      periodLockService.assertOpen(shopId, from);
      target.setWeeklyOffSchedule(
          applyWeeklyOff(target.getWeeklyOffSchedule(), from, request.getWeeklyOff().getDays()));
    }
    if (request.getWorkStart() != null) {
      target.setWorkStart(request.getWorkStart());
    }
    if (request.getWorkEnd() != null) {
      target.setWorkEnd(request.getWorkEnd());
    }
    if (request.getGraceMinutes() != null) {
      target.setGraceMinutes(request.getGraceMinutes());
    }
    if (request.getHalfDayBelowHours() != null) {
      target.setHalfDayBelowHours(request.getHalfDayBelowHours());
    }
    if (request.getOvertimeEnabled() != null) {
      target.setOvertimeEnabled(request.getOvertimeEnabled());
    }
    if (request.getOvertimeMultiplier() != null) {
      target.setOvertimeMultiplier(request.getOvertimeMultiplier());
    }
    if (request.getPerDayBasis() != null) {
      target.setPerDayBasis(request.getPerDayBasis());
    }
    if (request.getLeaveYearStartMonth() != null) {
      target.setLeaveYearStartMonth(request.getLeaveYearStartMonth());
    }
    if (request.getPayCycleStartDay() != null
        && request.getPayCycleStartDay() != PayCycle.startDay(target)) {
      // locked runs keep the ranges of the start day they were finalised with; DRAFT runs pick
      // up the new range on their next recalculation (open / finalise recalculate)
      if (periodLockService.hasLockedRun(shopId)) {
        throw new BaseException(ErrorCode.HR_RUN_STATE_CONFLICT, PAY_CYCLE_LOCKED_MESSAGE);
      }
      target.setPayCycleStartDay(request.getPayCycleStartDay());
    }
    HrSettings merged = withDefaults(target, shopId);
    validator.validateHours(merged.getWorkStart(), merged.getWorkEnd());

    target.setUpdatedAt(Instant.now());
    target.setUpdatedByUserId(userId);
    HrSettings saved;
    try {
      saved = settingsRepository.save(target);
    } catch (OptimisticLockingFailureException | DuplicateKeyException e) {
      // lost update, or two first saves raced on the unique shopId index
      throw concurrentUpdate();
    } catch (DataAccessException e) {
      log.error("Database error while saving HR settings for shop {}: {}", shopId, e.getMessage());
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error saving HR settings");
    }
    auditService.record(AuditEntry.of(shopId, userId, HrAuditAction.SETTINGS_CHANGED,
        "HR_SETTINGS", saved.getId(), before, snapshot(withDefaults(saved, shopId))));
    return toResponse(saved);
  }

  /** Empty-state guide (R13.6). Three cheap indexed reads. */
  public HrSetupStatusResponse setupStatus(String userId, String shopId) {
    guard.requireAny(userId, shopId);
    return new HrSetupStatusResponse(
        settingsRepository.existsByShopId(shopId),
        employeeRepository.countByShopIdAndStatus(shopId, EmployeeStatus.ACTIVE),
        attendanceDayRepository.existsByShopId(shopId));
  }

  /** Weekly-off days that apply in {@code period}: latest entry with effectiveFrom ≤ period. */
  public static List<DayOfWeek> weeklyOffFor(HrSettings settings, String period) {
    List<HrSettings.WeeklyOffEntry> schedule = settings != null ? settings.getWeeklyOffSchedule()
        : null;
    if (schedule == null || schedule.isEmpty()) {
      return List.of(DayOfWeek.SUNDAY);
    }
    HrSettings.WeeklyOffEntry match = null;
    for (HrSettings.WeeklyOffEntry entry : schedule) {
      if (entry.getEffectiveFrom().compareTo(period) <= 0
          && (match == null || entry.getEffectiveFrom().compareTo(match.getEffectiveFrom()) > 0)) {
        match = entry;
      }
    }
    // a period before the first entry falls back to the default
    return match != null ? List.copyOf(match.getDays()) : List.of(DayOfWeek.SUNDAY);
  }

  /**
   * The schedule with a new rule from {@code from} on. Entries starting on or after {@code from}
   * are replaced: the newest change wins for every later month. YYYY-MM strings sort by time.
   */
  static List<HrSettings.WeeklyOffEntry> applyWeeklyOff(
      List<HrSettings.WeeklyOffEntry> current, String from, List<DayOfWeek> days) {
    List<HrSettings.WeeklyOffEntry> schedule = new ArrayList<>();
    if (current == null || current.isEmpty()) {
      schedule.add(new HrSettings.WeeklyOffEntry(SCHEDULE_START, List.of(DayOfWeek.SUNDAY)));
    } else {
      schedule.addAll(current);
    }
    schedule.removeIf(e -> e.getEffectiveFrom().compareTo(from) >= 0);
    schedule.add(new HrSettings.WeeklyOffEntry(from, days.stream().sorted().toList()));
    schedule.sort(Comparator.comparing(HrSettings.WeeklyOffEntry::getEffectiveFrom));
    return schedule;
  }

  private HrSettingsResponse toResponse(HrSettings stored) {
    HrSettings effective = withDefaults(stored, null);
    HrSettingsResponse response = mapper.toResponse(effective);
    response.setSaved(stored != null);
    String currentPeriod = PayCycle.currentPeriod(PayCycle.startDay(effective));
    response.setCurrentWeeklyOffDays(weeklyOffFor(effective, currentPeriod));
    response.setCurrentSalaryPeriod(currentPeriod);
    return response;
  }

  /** Copy with every null replaced by its default; the stored object is not changed. */
  static HrSettings withDefaults(HrSettings stored, String shopId) {
    HrSettings s = new HrSettings();
    if (stored != null) {
      s.setId(stored.getId());
      s.setShopId(stored.getShopId());
      s.setRevision(stored.getRevision());
      s.setCreatedAt(stored.getCreatedAt());
      s.setUpdatedAt(stored.getUpdatedAt());
      s.setUpdatedByUserId(stored.getUpdatedByUserId());
    } else {
      s.setShopId(shopId);
    }
    List<HrSettings.WeeklyOffEntry> schedule =
        stored != null && stored.getWeeklyOffSchedule() != null
            && !stored.getWeeklyOffSchedule().isEmpty()
            ? stored.getWeeklyOffSchedule()
            : List.of(new HrSettings.WeeklyOffEntry(SCHEDULE_START, List.of(DayOfWeek.SUNDAY)));
    s.setWeeklyOffSchedule(new ArrayList<>(schedule));
    s.setWorkStart(value(stored != null ? stored.getWorkStart() : null, DEFAULT_WORK_START));
    s.setWorkEnd(value(stored != null ? stored.getWorkEnd() : null, DEFAULT_WORK_END));
    s.setGraceMinutes(value(stored != null ? stored.getGraceMinutes() : null,
        DEFAULT_GRACE_MINUTES));
    s.setHalfDayBelowHours(value(stored != null ? stored.getHalfDayBelowHours() : null,
        DEFAULT_HALF_DAY_BELOW_HOURS));
    s.setOvertimeEnabled(value(stored != null ? stored.getOvertimeEnabled() : null, false));
    s.setOvertimeMultiplier(value(stored != null ? stored.getOvertimeMultiplier() : null,
        DEFAULT_OVERTIME_MULTIPLIER));
    s.setPerDayBasis(value(stored != null ? stored.getPerDayBasis() : null,
        PerDayBasis.CALENDAR_DAYS));
    s.setLeaveYearStartMonth(value(stored != null ? stored.getLeaveYearStartMonth() : null,
        LeaveYearStartMonth.JANUARY));
    s.setPayCycleStartDay(PayCycle.startDay(stored));
    return s;
  }

  private static HrSettings newSettings(String shopId) {
    HrSettings s = new HrSettings();
    s.setShopId(shopId);
    s.setCreatedAt(Instant.now());
    return s;
  }

  private static Map<String, Object> snapshot(HrSettings s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("weeklyOffSchedule", s.getWeeklyOffSchedule().stream()
        .map(e -> e.getEffectiveFrom() + ":" + e.getDays()).toList());
    m.put("workStart", s.getWorkStart());
    m.put("workEnd", s.getWorkEnd());
    m.put("graceMinutes", s.getGraceMinutes());
    m.put("halfDayBelowHours", Objects.toString(s.getHalfDayBelowHours()));
    m.put("overtimeEnabled", s.getOvertimeEnabled());
    m.put("overtimeMultiplier", Objects.toString(s.getOvertimeMultiplier()));
    m.put("perDayBasis", String.valueOf(s.getPerDayBasis()));
    m.put("leaveYearStartMonth", String.valueOf(s.getLeaveYearStartMonth()));
    m.put("payCycleStartDay", s.getPayCycleStartDay());
    return m;
  }

  private static <T> T value(T value, T fallback) {
    return value != null ? value : fallback;
  }

  private static BaseException concurrentUpdate() {
    return new BaseException(ErrorCode.HR_CONCURRENT_UPDATE,
        ErrorCode.HR_CONCURRENT_UPDATE.getMessage());
  }
}
