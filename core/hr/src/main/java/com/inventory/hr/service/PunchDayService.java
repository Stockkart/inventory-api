package com.inventory.hr.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.repository.PunchRepository;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.utils.HrDateUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Punches → derived attendance days (design D2–D4). A derived write lands only when no day is
 * stored yet or the stored day is itself SELF_CHECK_IN with no more counted punches than the new
 * result: one upsert whose filter requires exactly that, so a hand-made mark (MANUAL,
 * BULK_PRESENT or null source) is never changed, and a racing older result never overwrites a
 * newer one. When the filter does not match, the upsert's insert hits the unique {@code (shopId,
 * employeeId, date)} index and is dropped.
 */
@Slf4j
@Service
public class PunchDayService {

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private PunchRepository punchRepository;

  @Autowired private HrPermissionGuard guard;

  @Autowired private HrSettingsService settingsService;

  /** One employee-day, oldest first (index {@code (shopId, employeeId, localDate)}). */
  public List<Punch> punchesOf(String shopId, String employeeId, String date) {
    return punchRepository.findByShopIdAndEmployeeIdAndLocalDateOrderByAtAsc(shopId, employeeId,
        date);
  }

  /** Derives the day now (Asia/Kolkata), with the employee's work end, else the shop's. */
  public static PunchDayDeriver.Derived derive(List<Punch> punches, Employee employee,
      String date, HrSettings settings) {
    Instant now = Instant.now();
    LocalDate today = now.atZone(HrDateUtils.ZONE).toLocalDate();
    LocalTime time = now.atZone(HrDateUtils.ZONE).toLocalTime();
    return PunchDayDeriver.derive(punches, HrDateUtils.parseDate(date), today, time,
        workEnd(employee, settings));
  }

  /** R4.4 on read, for a stored SELF_CHECK_IN day (the stored flag may be out of date). */
  public static boolean missingCheckOut(AttendanceDay day, Employee employee, HrSettings settings) {
    if (day == null || day.getSource() != AttendanceSource.SELF_CHECK_IN
        || day.getOutTime() != null) {
      return false;
    }
    Instant now = Instant.now();
    return PunchDayDeriver.isMissingCheckOut(day.getPunchCount(),
        HrDateUtils.parseDate(day.getDate()), now.atZone(HrDateUtils.ZONE).toLocalDate(),
        now.atZone(HrDateUtils.ZONE).toLocalTime(), workEnd(employee, settings));
  }

  /**
   * Writes the derived day when allowed (see class comment).
   *
   * @return the day as written, or null when nothing was written (no counted punch, a hand-made
   *     mark, or a newer derived result)
   */
  public AttendanceDay writeDerived(String shopId, Employee employee, String date,
      HrSettings settings, PunchDayDeriver.Derived derived) {
    if (derived == null || derived.isEmpty()) {
      return null;
    }
    AttendanceDay day = new AttendanceDay();
    day.setShopId(shopId);
    day.setEmployeeId(employee.getId());
    day.setDate(date);
    day.setPeriod(HrDateUtils.periodOf(date));
    day.setInTime(derived.inTime());
    day.setOutTime(derived.outTime());
    day.setWorkedMinutes(derived.workedMinutes());
    // the existing half-day rule (R2.5), as for a hand-made Present with times
    boolean auto = DayStatusResolver.isAutoHalfDay(AttendanceStatus.PRESENT,
        derived.workedMinutes(), settings);
    day.setStatus(auto ? AttendanceStatus.HALF_DAY : AttendanceStatus.PRESENT);
    day.setAutoHalfDay(auto ? Boolean.TRUE : null);
    day.setSource(AttendanceSource.SELF_CHECK_IN);
    day.setMarkedAt(Instant.now());
    day.setPunchCount(derived.punchCount());
    day.setMissingCheckOut(derived.missingCheckOut());

    Query filter = Query.query(Criteria.where("shopId").is(shopId)
        .and("employeeId").is(employee.getId()).and("date").is(date)
        .and("source").is(AttendanceSource.SELF_CHECK_IN)
        .orOperator(Criteria.where("punchCount").lte(derived.punchCount()),
            Criteria.where("punchCount").is(null)));
    Update update = new Update()
        .set("status", day.getStatus())
        .set("inTime", day.getInTime())
        .set("outTime", day.getOutTime())
        .set("workedMinutes", day.getWorkedMinutes())
        .set("autoHalfDay", day.getAutoHalfDay())
        .set("source", day.getSource())
        .set("period", day.getPeriod())
        .set("markedAt", day.getMarkedAt())
        .set("markedByUserId", null)
        .set("punchCount", day.getPunchCount())
        .set("missingCheckOut", day.getMissingCheckOut())
        .setOnInsert("shopId", shopId)
        .setOnInsert("employeeId", employee.getId())
        .setOnInsert("date", date);
    try {
      mongoTemplate.upsert(filter, update, AttendanceDay.class);
      return day;
    } catch (DuplicateKeyException e) {
      // a hand-made mark, or a derived day from more punches, is already stored: it wins
      return null;
    }
  }

  /**
   * Re-derives the given employee-days from their punches (R4.6: a hand-made mark was cleared).
   * One punches read for all of them; days without punches stay unmarked.
   *
   * @param keys {@code AttendanceService.key(employeeId, date)} values
   * @return the days written, by key
   */
  public Map<String, AttendanceDay> rederive(String shopId, Map<String, Employee> employees,
      Collection<String[]> keys, HrSettings settings) {
    Map<String, AttendanceDay> written = new HashMap<>();
    if (keys == null || keys.isEmpty()) {
      return written;
    }
    Set<String> ids = new LinkedHashSet<>();
    Set<String> dates = new LinkedHashSet<>();
    for (String[] k : keys) {
      ids.add(k[0]);
      dates.add(k[1]);
    }
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("employeeId").in(ids)
        .and("localDate").in(dates));
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS)
        .cursorBatchSize(HrQueryRunner.BULK_BATCH_SIZE);
    Map<String, List<Punch>> byDay = new HashMap<>();
    for (Punch p : mongoTemplate.find(query, Punch.class)) {
      byDay.computeIfAbsent(AttendanceService.key(p.getEmployeeId(), p.getLocalDate()),
          x -> new ArrayList<>()).add(p);
    }
    for (String[] k : keys) {
      String key = AttendanceService.key(k[0], k[1]);
      List<Punch> punches = byDay.get(key);
      Employee e = employees.get(k[0]);
      if (punches == null || e == null) {
        continue;
      }
      AttendanceDay day = writeDerived(shopId, e, k[1], settings,
          derive(punches, e, k[1], settings));
      if (day != null) {
        written.put(key, day);
      }
    }
    return written;
  }

  /**
   * Flags of the punches of {@code [from, to]} per {@code AttendanceService.key(employeeId,
   * date)}; only punches that carry a flag are read.
   */
  public Map<String, Set<PunchFlag>> flagsByEmployeeDate(String shopId,
      Collection<String> employeeIds, String from, String to, long maxTimeMs) {
    Map<String, Set<PunchFlag>> flags = new HashMap<>();
    if (employeeIds == null || employeeIds.isEmpty()) {
      return flags;
    }
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("employeeId")
        .in(employeeIds).and("localDate").gte(from).lte(to).and("flags.0").exists(true));
    query.fields().include("employeeId", "localDate", "flags");
    query.maxTimeMsec(maxTimeMs).cursorBatchSize(HrQueryRunner.BULK_BATCH_SIZE);
    for (Punch p : mongoTemplate.find(query, Punch.class)) {
      if (p.getFlags() == null || p.getFlags().isEmpty()) {
        continue;
      }
      flags.computeIfAbsent(AttendanceService.key(p.getEmployeeId(), p.getLocalDate()),
          x -> EnumSet.noneOf(PunchFlag.class)).addAll(p.getFlags());
    }
    return flags;
  }

  /**
   * {@code GET /attendance/punches?employeeId=&date=} (VIEW_ATTENDANCE): one employee's punches of
   * a day with distance and flags (R3.7). Scoped by the request's shop, so an id from another
   * shop is a 404.
   */
  public List<PunchResponse> listForEmployee(String userId, String shopId, String employeeId,
      String date) {
    guard.require(userId, shopId, HrPermission.VIEW_ATTENDANCE);
    if (!StringUtils.hasText(employeeId)) {
      throw new ValidationException("employeeId is required");
    }
    if (!HrDateUtils.isDate(date)) {
      throw new ValidationException("date must be a date like 2025-04-07");
    }
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("_id").is(employeeId));
    query.fields().include("workEnd");
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    Employee employee = mongoTemplate.findOne(query, Employee.class);
    if (employee == null) {
      throw new ResourceNotFoundException("Employee", "id", employeeId);
    }
    return toResponses(punchesOf(shopId, employeeId, date), employee, date,
        settingsService.effectiveSettings(shopId));
  }

  /** Punches oldest first, each marked counted or not by the 5-minute rule. */
  static List<PunchResponse> toResponses(List<Punch> punches, Employee employee, String date,
      HrSettings settings) {
    PunchDayDeriver.Derived derived = derive(punches, employee, date, settings);
    Set<Punch> counted = Collections.newSetFromMap(new IdentityHashMap<>());
    counted.addAll(derived.counted());
    List<Punch> sorted = new ArrayList<>(punches);
    sorted.sort(Comparator.comparing(Punch::getAt,
        Comparator.nullsLast(Comparator.naturalOrder())));
    List<PunchResponse> rows = new ArrayList<>(sorted.size());
    for (Punch p : sorted) {
      rows.add(new PunchResponse(p.getId(), p.getEmployeeId(), p.getAt(),
          p.getAt() != null ? PunchDayDeriver.hhmm(p.getAt()) : null, p.getLocalDate(),
          p.getSource(), p.getDirection(), p.getDistanceMetres(), p.getAccuracyMetres(),
          p.getFlags() != null ? List.copyOf(p.getFlags()) : List.of(), counted.contains(p)));
    }
    return rows;
  }

  static LocalTime workEnd(Employee employee, HrSettings settings) {
    String end = employee != null && employee.getWorkEnd() != null ? employee.getWorkEnd()
        : settings != null ? settings.getWorkEnd() : null;
    return HrDateUtils.parseTime(end);
  }
}
