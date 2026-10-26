package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrNotification;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.LeaveOrigin;
import com.inventory.hr.domain.model.enums.LeaveRequestStatus;
import com.inventory.hr.domain.model.enums.NotificationAudience;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.rest.dto.request.CreatePayrollRunRequest;
import com.inventory.hr.rest.dto.request.RecordAdvanceRequest;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import com.mongodb.ExplainVerbosity;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Design "Performance and capacity", verification plan (R14.3–R14.5): shops of 100 and 300
 * employees with three months of marked days (the plan says 12; 3 keeps the run under a minute
 * and covers every query, because each request reads one month), one leave per employee per
 * month, advances for 10 %, two finalised runs and a draft, and 1,000 notifications.
 *
 * <ul>
 *   <li>Round trips: a test-only {@link CommandListener} counts the commands each service call
 *       starts on the calling thread (hello, ping, endSessions, isMaster ignored) and asserts the
 *       HR part of the design budget (the "base 4" of the interceptors is not in a service
 *       call; the guard is a stub here).
 *   <li>Indexes: {@code explain("executionStats")} of the main queries shows no COLLSCAN.
 *   <li>Timings on the local Mongo: median of 5 (registers) or 3 (draft) after one warm-up.
 *   <li>Storage: average document sizes within twice the design estimate.
 * </ul>
 *
 * Numbers are printed as {@code [hr-perf] …} lines for design.md.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class HrPerformanceIT {

  private static final String OWNER = "owner-1";
  private static final List<YearMonth> MONTHS =
      List.of(YearMonth.of(2025, 1), YearMonth.of(2025, 2), YearMonth.of(2025, 3));
  private static final YearMonth DRAFT = YearMonth.of(2025, 3);
  private static final Set<String> IGNORED =
      Set.of("hello", "ismaster", "isMaster", "ping", "endSessions", "buildInfo");

  /** Commands started on the measuring thread while {@link #counting} is set. */
  private static final AtomicInteger COMMANDS = new AtomicInteger();
  private static final ThreadLocal<Boolean> COUNTING = ThreadLocal.withInitial(() -> false);
  private static final List<String> SEEN = new ArrayList<>();

  private static HrMongoItSupport it;

  @BeforeAll
  static void setUp() {
    CommandListener listener = new CommandListener() {
      @Override
      public void commandStarted(CommandStartedEvent event) {
        if (COUNTING.get() && !IGNORED.contains(event.getCommandName())) {
          COMMANDS.incrementAndGet();
          synchronized (SEEN) {
            SEEN.add(event.getCommandName() + ":" + collection(event));
          }
        }
      }
    };
    it = new HrMongoItSupport("perf", listener);
  }

  @AfterAll
  static void tearDown() {
    if (it != null) {
      it.close();
    }
  }

  private static String collection(CommandStartedEvent e) {
    org.bson.BsonValue c = e.getCommand().get(e.getCommandName());
    return c != null && c.isString() ? c.asString().getValue() : "";
  }

  /** Runs {@code call} with counting on and returns the number of commands it started. */
  private static <T> int count(String label, Supplier<T> call) {
    synchronized (SEEN) {
      SEEN.clear();
    }
    COMMANDS.set(0);
    COUNTING.set(true);
    try {
      call.get();
    } finally {
      COUNTING.set(false);
    }
    int n = COMMANDS.get();
    System.out.printf("[hr-perf] %s commands=%d %s%n", label, n, SEEN);
    return n;
  }

  /** Median wall time in ms of {@code runs} calls after one warm-up. */
  private static long medianMs(String label, int runs, Supplier<?> call) {
    call.get();
    long[] t = new long[runs];
    for (int i = 0; i < runs; i++) {
      long start = System.nanoTime();
      call.get();
      t[i] = (System.nanoTime() - start) / 1_000_000;
    }
    Arrays.sort(t);
    long median = t[runs / 2];
    System.out.printf("[hr-perf] %s median=%dms runs=%s%n", label, median, Arrays.toString(t));
    return median;
  }

  // ---- seed ----------------------------------------------------------------------------------

  /** Seeds one shop; returns its employees. */
  private static List<Employee> seed(String shop, int size) {
    List<Employee> staff = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      Employee e = new Employee();
      e.setShopId(shop);
      e.setCode(String.format("EMP-%03d", i + 1));
      e.setName("Staff " + (i + 1));
      e.setDesignation("Helper");
      e.setJoinDate("2024-06-01");
      e.setStatus(EmployeeStatus.ACTIVE);
      e.setSalaryRevisions(new ArrayList<>(List.of(new Employee.SalaryRevision("2024-06",
          PayType.MONTHLY, new BigDecimal(15000 + 10 * i), Instant.EPOCH, OWNER))));
      e.setCreatedAt(Instant.EPOCH);
      staff.add(e);
    }
    staff = new ArrayList<>(it.template.insertAll(staff));
    String casual = it.types.ensureDefaults(shop).stream()
        .filter(t -> Boolean.TRUE.equals(t.getPaid())).map(LeaveType::getId).findFirst()
        .orElseThrow();
    Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
    for (YearMonth m : MONTHS) {
      List<AttendanceDay> days = new ArrayList<>();
      List<LeaveRequest> leave = new ArrayList<>();
      LocalDate leaveDay = firstWednesday(m);
      for (Employee e : staff) {
        for (LocalDate d = m.atDay(1); !d.isAfter(m.atEndOfMonth()); d = d.plusDays(1)) {
          if (d.getDayOfWeek() == DayOfWeek.SUNDAY || d.equals(leaveDay)) {
            continue;
          }
          AttendanceDay day = new AttendanceDay();
          day.setShopId(shop);
          day.setEmployeeId(e.getId());
          day.setDate(d.toString());
          day.setPeriod(m.toString());
          day.setStatus(AttendanceStatus.PRESENT);
          day.setMarkedAt(longAgo);
          day.setMarkedByUserId(OWNER);
          days.add(day);
        }
        LeaveRequest r = new LeaveRequest();
        r.setShopId(shop);
        r.setEmployeeId(e.getId());
        r.setLeaveTypeId(casual);
        r.setFromDate(leaveDay.toString());
        r.setToDate(leaveDay.toString());
        r.setHalfDay(false);
        r.setDays(BigDecimal.ONE);
        r.setStatus(LeaveRequestStatus.APPROVED);
        r.setOrigin(LeaveOrigin.RECORDED);
        r.setRequestedByUserId(OWNER);
        r.setDecidedAt(longAgo);
        r.setDecidedByUserId(OWNER);
        r.setLeaveYear("2025");
        r.setCreatedAt(longAgo);
        leave.add(r);
      }
      it.template.insertAll(days);
      it.template.insertAll(leave);
    }
    for (int i = 0; i < size / 10; i++) {
      RecordAdvanceRequest adv = new RecordAdvanceRequest();
      adv.setEmployeeId(staff.get(i).getId());
      adv.setDate("2024-12-10");
      adv.setAmount(new BigDecimal("3000"));
      adv.setMonthlyDeduction(new BigDecimal("1000"));
      adv.setPaidBy("CASH");
      it.advances.record(OWNER, shop, adv);
    }
    List<HrNotification> notes = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      HrNotification n = new HrNotification();
      n.setShopId(shop);
      n.setAudience(i % 2 == 0 ? NotificationAudience.APPROVERS : NotificationAudience.USER);
      n.setRecipientUserId(i % 2 == 0 ? null : "login-" + (i % 50));
      n.setType(HrNotificationType.LEAVE_REQUESTED);
      n.setLeaveRequestId("lr-" + i);
      n.setText("Leave");
      n.setCreatedAt(Instant.now().minusSeconds(86_400L * (i % 60)));
      notes.add(n);
    }
    it.template.insertAll(notes);
    // January and February finalised oldest first, March left as the draft
    for (YearMonth m : MONTHS) {
      String runId = it.payroll.create(OWNER, shop, new CreatePayrollRunRequest(m.toString()))
          .getId();
      if (!m.equals(DRAFT)) {
        it.payroll.finalise(OWNER, shop, runId);
      }
    }
    return staff;
  }

  private static LocalDate firstWednesday(YearMonth m) {
    LocalDate d = m.atDay(1);
    while (d.getDayOfWeek() != DayOfWeek.WEDNESDAY) {
      d = d.plusDays(1);
    }
    return d;
  }

  // ---- the checks ----------------------------------------------------------------------------

  @Test
  void hundredEmployees() {
    run("perf-100", 100, 1_000, 1_000, 2_000);
  }

  @Test
  void threeHundredEmployees() {
    run("perf-300", 300, 1_000, 2_000, 3_000);
  }

  private void run(String shop, int size, long dailyMs, long monthMs, long draftMs) {
    long seedStart = System.nanoTime();
    List<Employee> staff = seed(shop, size);
    System.out.printf("[hr-perf] %s seeded in %d ms%n", shop,
        (System.nanoTime() - seedStart) / 1_000_000);
    String day = "2025-03-12";
    String period = DRAFT.toString();
    String draftRunId = it.payroll.list(OWNER, shop).stream()
        .filter(r -> period.equals(r.getPeriod())).findFirst().orElseThrow().getId();

    Map<String, Integer> budget = new LinkedHashMap<>();
    budget.put("daily", 6);
    budget.put("save", 7);
    budget.put("month", 6);
    // design 9; +1 employment re-check, +1 leave type read, +2 post-write overlap and balance
    // re-check (run B2: these stand in for transactions) = 13 (design.md Performance)
    budget.put("approve", 13);
    budget.put("recalculate", 13);
    // design 23 counted ~7 accounting trips; measured HR 22 (claim-then-check run re-read, two
    // still-claimed checks, straggler delete, response read; run B3) + accounting post 8 = 30,
    // +1 run B5: the system-account check of 1250/2400/5700 before the post (review M3),
    // +1 pay cycle: the settings read for the salary-month start day before the claim
    budget.put("finalise", 32);
    budget.put("payslip", 2);
    // HR 8 (stale-claim scan and the all:true id read added to the design's 6) + accounting
    // post 10 (AccountingPostingService: accounts, entry, sequence, balances, ledger) = 18,
    // +1 run B5: the system-account check of 2400 before the post (review M3)
    budget.put("payments", 19);
    Map<String, Integer> measured = new LinkedHashMap<>();

    measured.put("daily", count(shop + " daily", () -> it.attendance.daily(OWNER, shop, day)));
    List<SaveAttendanceDaysRequest.Row> rows = new ArrayList<>();
    for (Employee e : staff) {
      rows.add(new SaveAttendanceDaysRequest.Row(e.getId(), day, "ABSENT", null, null, null));
    }
    measured.put("save", count(shop + " save", () -> it.attendance.saveDays(OWNER, shop,
        new SaveAttendanceDaysRequest(rows))));
    measured.put("month", count(shop + " month",
        () -> it.attendance.month(OWNER, shop, period, null)));
    LeaveRequest pending = new LeaveRequest();
    pending.setShopId(shop);
    pending.setEmployeeId(staff.get(0).getId());
    pending.setLeaveTypeId(it.types.ensureDefaults(shop).stream()
        .filter(t -> Boolean.TRUE.equals(t.getPaid())).map(LeaveType::getId).findFirst()
        .orElseThrow());
    pending.setFromDate("2025-03-20");
    pending.setToDate("2025-03-20");
    pending.setHalfDay(false);
    pending.setDays(BigDecimal.ONE);
    pending.setStatus(LeaveRequestStatus.PENDING);
    pending.setOrigin(LeaveOrigin.SELF_SERVICE);
    pending.setRequestedByUserId("login-1");
    pending.setLeaveYear("2025");
    pending.setCreatedAt(Instant.now());
    String pendingId = it.template.insert(pending).getId();
    measured.put("approve", count(shop + " approve",
        () -> it.leave.approve(OWNER, shop, pendingId, null)));
    measured.put("recalculate", count(shop + " recalculate",
        () -> it.payroll.recalculate(OWNER, shop, draftRunId)));

    long daily = medianMs(shop + " daily", 5, () -> it.attendance.daily(OWNER, shop, day));
    long month = medianMs(shop + " month", 5,
        () -> it.attendance.month(OWNER, shop, period, null));
    long draft = medianMs(shop + " recalculate", 3,
        () -> it.payroll.recalculate(OWNER, shop, draftRunId));

    // the day saved as ABSENT above is a working day: put it back so finalise has no gaps
    List<SaveAttendanceDaysRequest.Row> back = new ArrayList<>();
    for (Employee e : staff) {
      back.add(new SaveAttendanceDaysRequest.Row(e.getId(), day, "PRESENT", null, null, null));
    }
    it.attendance.saveDays(OWNER, shop, new SaveAttendanceDaysRequest(back));
    measured.put("finalise", count(shop + " finalise",
        () -> it.payroll.finalise(OWNER, shop, draftRunId)));
    List<PayrollLine> lines = SalaryPaymentIT.lines(it, shop, draftRunId);
    measured.put("payslip", count(shop + " payslip", () -> it.payslips.staffPayslip(OWNER, shop,
        draftRunId, lines.get(0).getId())));
    measured.put("payments", count(shop + " payments", () -> it.payments.pay(OWNER, shop,
        draftRunId, SalaryPaymentIT.payRequest(List.of(), true))));
    long payslipMs = medianMs(shop + " payslip", 3, () -> it.payslips.staffPayslip(OWNER, shop,
        draftRunId, lines.get(0).getId()));

    List<String> over = new ArrayList<>();
    for (Map.Entry<String, Integer> e : measured.entrySet()) {
      if (e.getValue() > budget.get(e.getKey())) {
        over.add(e.getKey() + " " + e.getValue() + " > " + budget.get(e.getKey()));
      }
    }
    System.out.printf("[hr-perf] %s trips %s budget %s; ms daily=%d month=%d draft=%d"
        + " payslip=%d%n", shop, measured, budget, daily, month, draft, payslipMs);
    assertTrue(over.isEmpty(), "over the round-trip budget: " + over);
    assertTrue(daily < dailyMs, "daily register " + daily + " ms");
    assertTrue(month < monthMs, "month register " + month + " ms");
    assertTrue(draft < draftMs, "draft recalculation " + draft + " ms");

    assertIndexed(shop, draftRunId, staff.get(0).getId());
    assertStorage();
  }

  /** The main queries use an index: no COLLSCAN anywhere in the winning plan. */
  private void assertIndexed(String shop, String runId, String employeeId) {
    Map<String, Document[]> queries = new LinkedHashMap<>();
    queries.put("hr_attendance_days", new Document[] {
        new Document("shopId", shop).append("date", "2025-03-12"),
        new Document("shopId", shop).append("date",
            new Document("$gte", "2025-03-01").append("$lte", "2025-03-31")),
        new Document("shopId", shop).append("employeeId", employeeId).append("date",
            new Document("$gte", "2025-03-01").append("$lte", "2025-03-31"))});
    queries.put("hr_employees", new Document[] {
        new Document("shopId", shop).append("joinDate", new Document("$lte", "2025-03-31"))});
    queries.put("hr_leave_requests", new Document[] {
        new Document("shopId", shop).append("status", "APPROVED").append("fromDate",
            new Document("$gte", "2024-12-02").append("$lte", "2025-03-31"))});
    queries.put("hr_payroll_lines", new Document[] {
        new Document("shopId", shop).append("runId", runId),
        new Document("shopId", shop).append("employeeId", employeeId),
        // pay: the claim, the next unpaid ids, the batch (mark paid / release / lost claim)
        new Document("shopId", shop).append("runId", runId)
            .append("_id", new Document("$in", List.of(new ObjectId(), new ObjectId())))
            .append("paymentStatus", new Document("$in", java.util.Arrays.asList("PENDING", null))),
        new Document("shopId", shop).append("runId", runId)
            .append("paymentStatus", new Document("$in", java.util.Arrays.asList("PENDING", null))),
        new Document("shopId", shop).append("runId", runId).append("paymentBatchId", "b-1")
            .append("paymentStatus", "PAYING"),
        new Document("shopId", shop).append("runId", runId).append("paymentBatchId", "b-1")
            .append("paymentStatus", "PAID"),
        // undo and stale claims
        new Document("shopId", shop).append("_id", new ObjectId()).append("paymentStatus", "PAID"),
        new Document("shopId", shop).append("runId", runId)
            .append("paymentStatus", new Document("$in", List.of("PAYING", "UNDOING")))
            .append("paymentClaimedAt", new Document("$not",
                new Document("$gte", java.util.Date.from(java.time.Instant.now())))),
        new Document("shopId", shop).append("runId", runId).append("paymentBatchId", "b-1")
            .append("paymentStatus", "PAYING").append("paymentClaimedAt",
                java.util.Date.from(java.time.Instant.now()))});
    queries.put("hr_payroll_runs", new Document[] {
        new Document("shopId", shop).append("period", "2025-03")});
    queries.put("hr_advances", new Document[] {
        new Document("shopId", shop).append("employeeId", employeeId)});
    queries.put("hr_notifications", new Document[] {
        new Document("$or", List.of(
            new Document("shopId", shop).append("recipientUserId", "login-1"),
            new Document("shopId", shop).append("audience", "APPROVERS")))});
    for (Map.Entry<String, Document[]> e : queries.entrySet()) {
      for (Document filter : e.getValue()) {
        Document plan = it.template.getCollection(e.getKey()).find(filter)
            .explain(ExplainVerbosity.EXECUTION_STATS);
        String winning = plan.get("queryPlanner", Document.class).get("winningPlan").toString();
        System.out.printf("[hr-perf] explain %s %s -> %s%n", e.getKey(), filter.keySet(),
            winning.contains("IXSCAN") ? "IXSCAN" : winning);
        assertFalse(winning.contains("COLLSCAN"), e.getKey() + " " + filter.toJson());
      }
    }
    // the advance list without an employee: newest first straight from the (shopId, date) index
    Document advances = it.template.getCollection("hr_advances")
        .find(new Document("shopId", shop)).sort(new Document("date", -1).append("createdAt", -1))
        .limit(500)
        .explain(ExplainVerbosity.EXECUTION_STATS);
    String advancePlan =
        advances.get("queryPlanner", Document.class).get("winningPlan").toString();
    System.out.printf("[hr-perf] explain hr_advances [shopId] sort date -> %s%n",
        advancePlan.contains("IXSCAN") ? "IXSCAN" : advancePlan);
    assertFalse(advancePlan.contains("COLLSCAN"), "hr_advances by shop");
    assertFalse(advancePlan.contains("\"SORT\"") || advancePlan.contains("stage=SORT"),
        "hr_advances by shop sorts in memory: " + advancePlan);
  }

  /** Average document size within twice the design estimate (design sizing table). */
  private void assertStorage() {
    Map<String, Integer> estimate = Map.of("hr_attendance_days", 350, "hr_payroll_lines", 2000,
        "hr_leave_requests", 450, "hr_notifications", 300);
    for (Map.Entry<String, Integer> e : estimate.entrySet()) {
      Document stats = it.template.getDb().runCommand(new Document("collStats", e.getKey()));
      Number avg = stats.get("avgObjSize", Number.class);
      System.out.printf("[hr-perf] storage %s avgObjSize=%s count=%s totalIndexSize=%s%n",
          e.getKey(), avg, stats.get("count"), stats.get("totalIndexSize"));
      assertTrue(avg == null || avg.doubleValue() <= 2.0 * e.getValue(),
          e.getKey() + " average " + avg + " bytes");
    }
  }
}
