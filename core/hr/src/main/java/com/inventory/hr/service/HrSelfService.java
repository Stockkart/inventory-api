package com.inventory.hr.service;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.mapper.EmployeeMapper;
import com.inventory.hr.rest.dto.request.RequestLeaveRequest;
import com.inventory.hr.rest.dto.response.EmployeeResponse;
import com.inventory.hr.rest.dto.response.HrNotificationsResponse;
import com.inventory.hr.rest.dto.response.LeavePreviewResponse;
import com.inventory.hr.rest.dto.response.LeaveRequestResponse;
import com.inventory.hr.rest.dto.response.LeaveBalanceRow;
import com.inventory.hr.rest.dto.response.MonthRegisterResponse;
import com.inventory.hr.rest.dto.response.MyLeaveResponse;
import com.inventory.hr.rest.dto.response.MyPayslipResponse;
import com.inventory.hr.utils.HrDateUtils;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Self-service for {@code /api/v1/hr/me/**} (R1.5, R1.6, R6.5, R10.5). The employee is always
 * the caller's own, found by {@link SelfServiceResolver} from the request's shop and the caller
 * (never an id from the path or body); a login without an employee gets 404 {@code
 * HR_NOT_LINKED}, except for notifications, which every member of the shop has.
 */
@Service
public class HrSelfService {

  @Autowired private SelfServiceResolver selfServiceResolver;

  @Autowired private EmployeeMapper employeeMapper;

  @Autowired private AttendanceService attendanceService;

  @Autowired private LeaveService leaveService;

  @Autowired private LeaveBalanceService leaveBalanceService;

  @Autowired private PayslipService payslipService;

  @Autowired private HrNotificationService notificationService;

  /**
   * {@code GET /me}: own pay is shown (R10.5); bank account and UPI stay masked as on list rows,
   * because the screen is often opened on a shared shop device.
   */
  public EmployeeResponse me(String shopId, String userId) {
    return employeeMapper.toListRow(selfServiceResolver.resolve(shopId, userId), true);
  }

  /** {@code GET /me/attendance?period=}: one row, the current month by default. */
  public MonthRegisterResponse attendance(String shopId, String userId, String period) {
    Employee self = selfServiceResolver.resolve(shopId, userId);
    return attendanceService.monthForSelf(shopId, self,
        StringUtils.hasText(period) ? period : HrDateUtils.currentPeriod());
  }

  /** {@code GET /me/leave?year=}: own balances and requests. */
  public MyLeaveResponse leave(String shopId, String userId, String year) {
    Employee self = selfServiceResolver.resolve(shopId, userId);
    List<LeaveBalanceRow> rows = leaveBalanceService.listForSelf(shopId, self,
        StringUtils.hasText(year) ? year : null);
    return new MyLeaveResponse(rows.isEmpty() ? null : rows.get(0),
        leaveService.requestsOf(shopId, self));
  }

  public LeavePreviewResponse previewLeave(String shopId, String userId, String leaveTypeId,
      String from, String to, Boolean halfDay) {
    return leaveService.previewForSelf(shopId, userId, leaveTypeId, from, to, halfDay);
  }

  public LeaveRequestResponse requestLeave(String shopId, String userId,
      RequestLeaveRequest request) {
    if (request == null) {
      throw new ValidationException("Leave details are required");
    }
    return leaveService.requestForSelf(shopId, userId, request);
  }

  public LeaveRequestResponse cancelLeave(String shopId, String userId, String id, String note) {
    return leaveService.cancelOwn(shopId, userId, id, note);
  }

  public List<MyPayslipResponse> payslips(String shopId, String userId) {
    return payslipService.ownPayslips(shopId, userId);
  }

  public HrFile payslipPdf(String shopId, String userId, String lineId) {
    return payslipService.ownPayslip(shopId, userId, lineId);
  }

  /** Any member of the shop, linked or not. {@code since} is an ISO-8601 instant. */
  public HrNotificationsResponse notifications(String shopId, String userId, String since) {
    return notificationService.listForCaller(userId, shopId, parseSince(since));
  }

  public void markNotificationRead(String shopId, String userId, String id) {
    notificationService.markRead(userId, shopId, id);
  }

  public long markAllNotificationsRead(String shopId, String userId) {
    return notificationService.markAllRead(userId, shopId);
  }

  static Instant parseSince(String since) {
    if (!StringUtils.hasText(since)) {
      return null;
    }
    try {
      return Instant.parse(since.trim());
    } catch (DateTimeParseException e) {
      throw new ValidationException("since must be a time like 2025-05-01T10:15:30Z");
    }
  }
}
