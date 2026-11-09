package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Holiday;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.LeaveOrigin;
import com.inventory.hr.domain.model.enums.LeaveRequestStatus;
import com.inventory.hr.domain.model.enums.LeaveYearStartMonth;
import com.inventory.hr.domain.repository.LeaveRequestRepository;
import com.inventory.hr.domain.repository.LeaveTypeRepository;
import com.inventory.hr.rest.dto.request.RecordLeaveRequest;
import com.inventory.hr.rest.dto.request.RequestLeaveRequest;
import com.inventory.hr.rest.dto.response.LeaveRequestResponse;
import com.inventory.hr.validation.LeaveRequestValidator;
import com.mongodb.client.result.DeleteResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveServiceTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private LeaveRequestRepository leaveRequestRepository;
  @Mock private LeaveTypeRepository leaveTypeRepository;
  @Mock private HrPermissionGuard guard;
  @Mock private SelfServiceResolver selfServiceResolver;
  @Mock private HrSettingsService settingsService;
  @Mock private LeaveBalanceService balanceService;
  @Mock private PeriodLockService periodLockService;
  @Mock private HrAuditService auditService;
  @Mock private HrNotificationService notificationService;
  @Spy private LeaveRequestValidator validator = new LeaveRequestValidator();
  @Spy private HrQueryRunner queryRunner = new HrQueryRunner();
  @InjectMocks private LeaveService service;

  private final LeaveType casual = type("casual", true, "12");
  private final LeaveType unpaid = type("unpaid", false, null);

  private static LeaveType type(String id, boolean paid, String quota) {
    LeaveType t = new LeaveType();
    t.setId(id);
    t.setName(id.equals("casual") ? "Casual" : "Unpaid leave");
    t.setPaid(paid);
    t.setYearlyQuotaDays(quota != null ? new BigDecimal(quota) : null);
    t.setActive(true);
    return t;
  }

  private static Employee employee() {
    Employee e = new Employee();
    e.setId("e1");
    e.setCode("EMP-001");
    e.setName("Ravi");
    e.setJoinDate("2024-01-01");
    e.setUserId("login-e1");
    return e;
  }

  @BeforeEach
  void setUp() {
    when(settingsService.effectiveSettings(SHOP))
        .thenReturn(HrSettingsService.withDefaults(null, SHOP));
    when(leaveTypeRepository.findByShopIdAndId(SHOP, "casual")).thenReturn(Optional.of(casual));
    when(leaveTypeRepository.findByShopIdAndId(SHOP, "unpaid")).thenReturn(Optional.of(unpaid));
    when(mongoTemplate.findOne(any(Query.class), eq(Employee.class))).thenReturn(employee());
    when(mongoTemplate.find(any(Query.class), eq(Holiday.class))).thenReturn(List.of());
    when(mongoTemplate.find(any(Query.class), eq(Employee.class))).thenReturn(List.of(employee()));
    when(mongoTemplate.exists(any(Query.class), eq(LeaveRequest.class))).thenReturn(false);
    when(mongoTemplate.remove(any(Query.class), eq(AttendanceDay.class)))
        .thenReturn(DeleteResult.acknowledged(1));
    when(leaveRequestRepository.insert(any(LeaveRequest.class))).thenAnswer(i -> {
      LeaveRequest r = i.getArgument(0);
      r.setId("new");
      return r;
    });
    when(balanceService.balanceFor(eq(SHOP), any(Employee.class), any(LeaveType.class),
        anyString(), any(LeaveYearStartMonth.class)))
        .thenReturn(new LeaveBalanceService.Balance(new BigDecimal("12"), BigDecimal.ZERO,
            new BigDecimal("12")));
  }

  /** Fri 4 to Mon 7 April 2025: Sunday 6 is a week-off, so 3 working days. */
  private static RecordLeaveRequest record(String type) {
    return new RecordLeaveRequest("e1", type, "2025-04-04", "2025-04-07", null, "family");
  }

  @Test
  void recordedLeaveIsApprovedCountsWorkingDaysAndCleansUpOnlyThoseMarks() {
    LeaveRequestResponse r = service.record(USER, SHOP, record("casual"));

    assertEquals(LeaveRequestStatus.APPROVED, r.getStatus());
    assertEquals(LeaveOrigin.RECORDED, r.getOrigin());
    assertEquals(0, r.getDays().compareTo(new BigDecimal("3")));
    assertEquals("2025", r.getLeaveYear());
    verify(guard).require(USER, SHOP, HrPermission.MARK_ATTENDANCE);
    verify(periodLockService).assertOpen(eq(SHOP), anyCollection());
    ArgumentCaptor<Query> removed = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).remove(removed.capture(), eq(AttendanceDay.class));
    Document date = (Document) removed.getValue().getQueryObject().get("date");
    assertEquals(List.of("2025-04-04", "2025-04-05", "2025-04-07"), date.get("$in"));
    verify(notificationService).notifyUser(eq(SHOP), eq("login-e1"),
        eq(HrNotificationType.LEAVE_APPROVED), eq("new"), anyString());
  }

  @Test
  void aPaidTypeOverItsBalanceTellsTheUserToChooseUnpaid() {
    when(balanceService.balanceFor(eq(SHOP), any(Employee.class), eq(casual), anyString(),
        any(LeaveYearStartMonth.class))).thenReturn(new LeaveBalanceService.Balance(
            new BigDecimal("12"), new BigDecimal("10"), new BigDecimal("2")));

    BaseException e = assertThrows(BaseException.class,
        () -> service.record(USER, SHOP, record("casual")));

    assertEquals(ErrorCode.HR_LEAVE_BALANCE_EXCEEDED, e.getErrorCode());
    assertTrue(e.getMessage().contains("unpaid"));
    verify(leaveRequestRepository, never()).insert(any(LeaveRequest.class));
  }

  @Test
  void unpaidLeaveHasNoBalanceCheck() {
    service.record(USER, SHOP, record("unpaid"));
    verify(balanceService, never()).balanceFor(anyString(), any(), any(), anyString(), any());
  }

  @Test
  void anOverlapIs409() {
    when(mongoTemplate.exists(any(Query.class), eq(LeaveRequest.class))).thenReturn(true);

    BaseException e = assertThrows(BaseException.class,
        () -> service.record(USER, SHOP, record("unpaid")));
    assertEquals(ErrorCode.HR_LEAVE_OVERLAP, e.getErrorCode());
  }

  @Test
  void aLockedMonthRejectsRecordedLeave() {
    doThrow(new BaseException(ErrorCode.HR_PERIOD_LOCKED, "locked"))
        .when(periodLockService).assertOpen(eq(SHOP), anyCollection());

    assertThrows(BaseException.class, () -> service.record(USER, SHOP, record("unpaid")));
    verify(leaveRequestRepository, never()).insert(any(LeaveRequest.class));
  }

  @Test
  void aRequestMayNotCrossTheLeaveYearOrCoverOnlyOffDays() {
    assertThrows(ValidationException.class, () -> service.record(USER, SHOP,
        new RecordLeaveRequest("e1", "unpaid", "2025-12-30", "2026-01-02", null, null)));
    // Sunday only
    assertThrows(ValidationException.class, () -> service.record(USER, SHOP,
        new RecordLeaveRequest("e1", "unpaid", "2025-04-06", "2025-04-06", null, null)));
  }

  @Test
  void aSelfRequestIsPendingAndNotifiesApprovers() {
    when(selfServiceResolver.resolve(SHOP, "login-e1")).thenReturn(employee());

    LeaveRequestResponse r = service.requestForSelf(SHOP, "login-e1",
        new RequestLeaveRequest("casual", "2025-04-08", "2025-04-08", true, null));

    assertEquals(LeaveRequestStatus.PENDING, r.getStatus());
    assertEquals(0, r.getDays().compareTo(new BigDecimal("0.5")));
    verify(notificationService).notifyApprovers(eq(SHOP), eq(HrNotificationType.LEAVE_REQUESTED),
        eq("new"), anyString());
    verify(mongoTemplate, never()).remove(any(Query.class), eq(AttendanceDay.class));
  }

  private static LeaveRequest stored(LeaveRequestStatus status) {
    LeaveRequest r = new LeaveRequest();
    r.setId("l1");
    r.setShopId(SHOP);
    r.setEmployeeId("e1");
    r.setLeaveTypeId("casual");
    r.setFromDate("2025-04-07");
    r.setToDate("2025-04-08");
    r.setStatus(status);
    r.setRequestedByUserId("login-e1");
    return r;
  }

  @Test
  void approveChecksTheLockThenClaimsThenCleansUp() {
    when(leaveRequestRepository.findByShopIdAndId(SHOP, "l1"))
        .thenReturn(Optional.of(stored(LeaveRequestStatus.PENDING)));
    LeaveRequest approved = stored(LeaveRequestStatus.APPROVED);
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(LeaveRequest.class))).thenReturn(approved);

    service.approve(USER, SHOP, "l1", "ok");

    InOrder order = inOrder(periodLockService, mongoTemplate);
    order.verify(periodLockService).assertOpen(eq(SHOP), anyCollection());
    order.verify(mongoTemplate).findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(LeaveRequest.class));
    order.verify(mongoTemplate).remove(any(Query.class), eq(AttendanceDay.class));
    verify(guard).require(USER, SHOP, HrPermission.APPROVE_LEAVE);
    verify(notificationService).notifyUser(eq(SHOP), eq("login-e1"),
        eq(HrNotificationType.LEAVE_APPROVED), eq("l1"), anyString());
  }

  @Test
  void approvingADecidedRequestOrLosingTheRaceIs409() {
    when(leaveRequestRepository.findByShopIdAndId(SHOP, "l1"))
        .thenReturn(Optional.of(stored(LeaveRequestStatus.REJECTED)));
    BaseException e = assertThrows(BaseException.class,
        () -> service.approve(USER, SHOP, "l1", null));
    assertEquals(ErrorCode.HR_LEAVE_STATE_CONFLICT, e.getErrorCode());

    when(leaveRequestRepository.findByShopIdAndId(SHOP, "l1"))
        .thenReturn(Optional.of(stored(LeaveRequestStatus.PENDING)));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(LeaveRequest.class))).thenReturn(null);
    e = assertThrows(BaseException.class, () -> service.approve(USER, SHOP, "l1", null));
    assertEquals(ErrorCode.HR_LEAVE_STATE_CONFLICT, e.getErrorCode());
    verify(mongoTemplate, never()).remove(any(Query.class), eq(AttendanceDay.class));
  }

  @Test
  void cancellingApprovedLeaveChecksTheLockButPendingDoesNot() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(LeaveRequest.class)))
        .thenReturn(stored(LeaveRequestStatus.CANCELLED));
    when(leaveRequestRepository.findByShopIdAndId(SHOP, "l1"))
        .thenReturn(Optional.of(stored(LeaveRequestStatus.PENDING)));

    service.cancel(USER, SHOP, "l1", null);
    verify(periodLockService, never()).assertOpen(anyString(), anyCollection());
    verify(guard).requireOneOf(USER, SHOP, HrPermission.APPROVE_LEAVE,
        HrPermission.MARK_ATTENDANCE);

    when(leaveRequestRepository.findByShopIdAndId(SHOP, "l1"))
        .thenReturn(Optional.of(stored(LeaveRequestStatus.APPROVED)));
    service.cancel(USER, SHOP, "l1", null);
    verify(periodLockService).assertOpen(eq(SHOP), anyCollection());
  }

  @Test
  void cancelOwnOfAnotherEmployeesRequestIs404() {
    Employee other = employee();
    other.setId("e2");
    when(selfServiceResolver.resolve(SHOP, "login-e2")).thenReturn(other);
    when(leaveRequestRepository.findByShopIdAndId(SHOP, "l1"))
        .thenReturn(Optional.of(stored(LeaveRequestStatus.PENDING)));

    assertThrows(ResourceNotFoundException.class,
        () -> service.cancelOwn(SHOP, "login-e2", "l1", null));
    verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(LeaveRequest.class));
  }

  @Test
  void aRaceFoundAfterTheInsertTakesTheInsertBack() {
    // the pre-check sees nothing, the recheck after the insert sees a concurrent request
    when(mongoTemplate.exists(any(Query.class), eq(LeaveRequest.class))).thenReturn(false, true);

    BaseException e = assertThrows(BaseException.class,
        () -> service.record(USER, SHOP, record("unpaid")));

    assertEquals(ErrorCode.HR_LEAVE_OVERLAP, e.getErrorCode());
    verify(mongoTemplate).remove(any(Query.class), eq(LeaveRequest.class));
    verify(mongoTemplate, never()).remove(any(Query.class), eq(AttendanceDay.class));
  }

  @Test
  void anApprovalThatPushesThePaidBalanceBelowZeroGoesBackToPending() {
    when(leaveRequestRepository.findByShopIdAndId(SHOP, "l1"))
        .thenReturn(Optional.of(stored(LeaveRequestStatus.PENDING)));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(LeaveRequest.class)))
        .thenReturn(stored(LeaveRequestStatus.APPROVED));
    when(balanceService.balanceFor(eq(SHOP), any(Employee.class), eq(casual), anyString(),
        any(LeaveYearStartMonth.class))).thenReturn(
            new LeaveBalanceService.Balance(new BigDecimal("12"), new BigDecimal("10"),
                new BigDecimal("2")),
            new LeaveBalanceService.Balance(new BigDecimal("12"), new BigDecimal("13"),
                new BigDecimal("-1")));

    BaseException e = assertThrows(BaseException.class,
        () -> service.approve(USER, SHOP, "l1", null));

    assertEquals(ErrorCode.HR_LEAVE_BALANCE_EXCEEDED, e.getErrorCode());
    verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class),
        eq(LeaveRequest.class));
    verify(mongoTemplate, never()).remove(any(Query.class), eq(AttendanceDay.class));
  }

  @Test
  void anotherShopsEmployeeIs404() {
    when(mongoTemplate.findOne(any(Query.class), eq(Employee.class))).thenReturn(null);

    assertThrows(ResourceNotFoundException.class,
        () -> service.record(USER, SHOP, record("unpaid")));
  }
}
