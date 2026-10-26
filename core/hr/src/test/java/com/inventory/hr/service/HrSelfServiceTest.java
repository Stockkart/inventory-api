package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.mapper.EmployeeMapper;
import com.inventory.hr.rest.dto.response.HrNotificationsResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@code /me/**}: the caller's own employee only; notifications for every member (task 10.1). */
@ExtendWith(MockitoExtension.class)
class HrSelfServiceTest {

  @Mock private SelfServiceResolver selfServiceResolver;
  @Mock private EmployeeMapper employeeMapper;
  @Mock private AttendanceService attendanceService;
  @Mock private HrNotificationService notificationService;
  @InjectMocks private HrSelfService service;

  @Test
  void meIsTheResolvedEmployeeWithOwnPayAndMaskedBankDetails() {
    Employee self = new Employee();
    self.setId("e1");
    when(selfServiceResolver.resolve("s1", "u1")).thenReturn(self);

    service.me("s1", "u1");

    verify(employeeMapper).toListRow(self, true);
  }

  @Test
  void anUnlinkedLoginGetsNoAttendance() {
    when(selfServiceResolver.resolve("s1", "u1"))
        .thenThrow(new BaseException(ErrorCode.HR_NOT_LINKED, "not linked"));

    BaseException e = assertThrows(BaseException.class,
        () -> service.attendance("s1", "u1", "2025-04"));

    assertEquals(ErrorCode.HR_NOT_LINKED, e.getErrorCode());
    verify(attendanceService, never()).monthForSelf(any(), any(), any());
  }

  @Test
  void notificationsDoNotNeedALinkedEmployee() {
    when(notificationService.listForCaller("u1", "s1", Instant.parse("2025-05-01T10:00:00Z")))
        .thenReturn(new HrNotificationsResponse(List.of(), null));

    service.notifications("s1", "u1", "2025-05-01T10:00:00Z");

    verify(selfServiceResolver, never()).resolve(any(), any());
  }

  @Test
  void sinceIsAnIsoInstant() {
    assertNull(HrSelfService.parseSince(" "));
    assertEquals(Instant.parse("2025-05-01T10:00:00Z"),
        HrSelfService.parseSince("2025-05-01T10:00:00Z"));
    assertThrows(ValidationException.class, () -> HrSelfService.parseSince("yesterday"));
  }
}
