package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.LeaveYearStartMonth;
import com.inventory.hr.rest.dto.response.LeaveBalanceRow;
import com.inventory.hr.utils.LeaveYearUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class LeaveBalanceServiceTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private HrPermissionGuard guard;
  @Mock private HrSettingsService settingsService;
  @Mock private LeaveTypeService leaveTypeService;
  @Spy private HrQueryRunner queryRunner = new HrQueryRunner();
  @InjectMocks private LeaveBalanceService service;

  @Test
  void leaveYearNamesFollowTheStartMonth() {
    assertEquals("2025", LeaveYearUtils.leaveYearOf(LocalDate.of(2025, 3, 31),
        LeaveYearStartMonth.JANUARY));
    assertEquals("2024-25", LeaveYearUtils.leaveYearOf(LocalDate.of(2025, 3, 31),
        LeaveYearStartMonth.APRIL));
    assertEquals("2025-26", LeaveYearUtils.leaveYearOf(LocalDate.of(2025, 4, 1),
        LeaveYearStartMonth.APRIL));
    assertEquals("2099-00", LeaveYearUtils.leaveYearOf(LocalDate.of(2099, 5, 1),
        LeaveYearStartMonth.APRIL));
    assertEquals(LocalDate.of(2025, 4, 1), LeaveYearUtils.start("2025-26",
        LeaveYearStartMonth.APRIL));
    assertNull(LeaveYearUtils.start("2025-27", LeaveYearStartMonth.APRIL));
    assertNull(LeaveYearUtils.start("2025-26", LeaveYearStartMonth.JANUARY));
  }

  @Test
  void aYearThatDoesNotFitTheSettingIs400() {
    assertThrows(ValidationException.class,
        () -> LeaveBalanceService.requireYear("2025", LeaveYearStartMonth.APRIL));
  }

  @Test
  void firstYearQuotaIsProratedAndRoundedDownToAHalfDay() {
    LocalDate start = LocalDate.of(2025, 1, 1);
    LocalDate end = LocalDate.of(2025, 12, 31);
    // 1 July: 184 of 365 days left → 12 × 184 / 365 = 6.049 → 6.0
    assertEquals(new BigDecimal("6.0"), LeaveBalanceService.proratedQuota(new BigDecimal("12"),
        "2025-07-01", start, end));
    // 1 Feb: 334 / 365 × 12 = 10.98 → 10.5
    assertEquals(new BigDecimal("10.5"), LeaveBalanceService.proratedQuota(new BigDecimal("12"),
        "2025-02-01", start, end));
    assertEquals(new BigDecimal("12"), LeaveBalanceService.proratedQuota(new BigDecimal("12"),
        "2024-06-01", start, end));
    assertEquals(BigDecimal.ZERO, LeaveBalanceService.proratedQuota(new BigDecimal("12"),
        "2026-01-02", start, end));
  }

  @Test
  void usedCountsOnlyApprovedDaysOfTheType() {
    LeaveRequest approved = request("t1", "APPROVED", "2");
    LeaveRequest pending = request("t1", "PENDING", "3");
    LeaveRequest other = request("t2", "APPROVED", "1");
    assertEquals(new BigDecimal("2"),
        LeaveBalanceService.usedDays(List.of(approved, pending, other), "t1"));
  }

  @Test
  void listUsesOneAggregationAndShowsQuotaUsedAndAvailable() {
    when(settingsService.effectiveSettings(SHOP))
        .thenReturn(HrSettingsService.withDefaults(null, SHOP));
    LeaveType casual = new LeaveType();
    casual.setId("t1");
    casual.setName("Casual");
    casual.setPaid(true);
    casual.setYearlyQuotaDays(new BigDecimal("12"));
    LeaveType unpaid = new LeaveType();
    unpaid.setId("t2");
    unpaid.setName("Unpaid leave");
    unpaid.setPaid(false);
    when(leaveTypeService.ensureDefaults(SHOP)).thenReturn(List.of(casual, unpaid));
    Employee e = new Employee();
    e.setId("e1");
    e.setCode("EMP-001");
    e.setName("Ravi");
    e.setJoinDate("2020-01-01");
    when(mongoTemplate.find(any(Query.class), eq(Employee.class))).thenReturn(List.of(e));
    Document row = new Document("_id", new Document("employeeId", "e1").append("leaveTypeId",
        "t1")).append("used", new Decimal128(new BigDecimal("2.5")));
    when(mongoTemplate.aggregate(any(Aggregation.class), eq(LeaveRequest.class),
        eq(Document.class))).thenReturn(new AggregationResults<>(List.of(row), new Document()));

    List<LeaveBalanceRow> rows = service.list(USER, SHOP, "2025");

    verify(guard).require(USER, SHOP, HrPermission.VIEW_ATTENDANCE);
    LeaveBalanceRow.Balance c = rows.get(0).getBalances().get(0);
    assertEquals(new BigDecimal("12"), c.getQuota());
    assertEquals(new BigDecimal("2.5"), c.getUsed());
    assertEquals(new BigDecimal("9.5"), c.getAvailable());
    LeaveBalanceRow.Balance u = rows.get(0).getBalances().get(1);
    assertNull(u.getQuota());
    assertNull(u.getAvailable());
    verify(mongoTemplate).aggregate(any(Aggregation.class), eq(LeaveRequest.class),
        eq(Document.class));
  }

  private static LeaveRequest request(String type, String status, String days) {
    LeaveRequest r = new LeaveRequest();
    r.setLeaveTypeId(type);
    r.setStatus(com.inventory.hr.domain.model.enums.LeaveRequestStatus.valueOf(status));
    r.setDays(new BigDecimal(days));
    return r;
  }
}
