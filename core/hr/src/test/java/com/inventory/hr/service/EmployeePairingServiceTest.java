package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static com.inventory.hr.HrTestFixtures.all;
import static com.inventory.hr.HrTestFixtures.employee;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.UnpairReason;
import com.inventory.hr.rest.dto.response.PairingCodeResponse;
import com.inventory.hr.rest.dto.response.PairingStateResponse;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Manager and self pairing routes (R2.1, R2.12, R2.13, R3.1): who may call, and as whom. */
@ExtendWith(MockitoExtension.class)
class EmployeePairingServiceTest {

  private static final String EMPLOYEE = "e1";

  @Mock private HrPermissionGuard guard;
  @Mock private SelfServiceResolver selfServiceResolver;
  @Mock private DevicePairingService pairingService;
  @InjectMocks private EmployeePairingService service;

  private static PairingStateResponse notPaired() {
    PairingStateResponse r = new PairingStateResponse();
    r.setState(PairingStateResponse.State.NOT_PAIRED);
    return r;
  }

  private void denied() {
    when(guard.require(eq(USER), eq(SHOP), eq(HrPermission.ADD_EDIT_EMPLOYEES)))
        .thenThrow(new BaseException(ErrorCode.ACCESS_DENIED, HrPermissionGuard.DENIED));
  }

  // ---- manager -------------------------------------------------------------------------------

  @Test
  void theSectionNeedsAddEditEmployees() {
    denied();

    BaseException e = assertThrows(BaseException.class, () -> service.state(USER, SHOP, EMPLOYEE));

    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    verifyNoInteractions(pairingService);
  }

  @Test
  void aCodeNeedsAddEditEmployees() {
    denied();

    assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(BaseException.class,
        () -> service.createCode(USER, SHOP, EMPLOYEE)).getErrorCode());
    verifyNoInteractions(pairingService);
  }

  @Test
  void unpairNeedsAddEditEmployees() {
    denied();

    assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(BaseException.class,
        () -> service.unpair(USER, SHOP, EMPLOYEE)).getErrorCode());
    verifyNoInteractions(pairingService);
  }

  @Test
  void aManagersCodeIsCreatedByTheManager() {
    when(guard.require(USER, SHOP, HrPermission.ADD_EDIT_EMPLOYEES)).thenReturn(all());
    PairingCodeResponse code = new PairingCodeResponse("ABCDEFGH",
        "https://stockkart.in/attendance/pair#ABCDEFGH", Instant.parse("2025-04-03T09:27:00Z"));
    when(pairingService.createCode(SHOP, EMPLOYEE, USER)).thenReturn(code);

    assertSame(code, service.createCode(USER, SHOP, EMPLOYEE));
  }

  @Test
  void aManualUnpairIsRecordedWithReasonManualAndTheManagerThenReturnsTheState() {
    when(guard.require(USER, SHOP, HrPermission.ADD_EDIT_EMPLOYEES)).thenReturn(all());
    when(pairingService.unpairActive(SHOP, EMPLOYEE, UnpairReason.MANUAL, USER))
        .thenReturn(Optional.empty());
    when(pairingService.pairingState(SHOP, EMPLOYEE)).thenReturn(notPaired());

    PairingStateResponse r = service.unpair(USER, SHOP, EMPLOYEE);

    assertEquals(PairingStateResponse.State.NOT_PAIRED, r.getState());
    verify(pairingService).unpairActive(SHOP, EMPLOYEE, UnpairReason.MANUAL, USER);
  }

  @Test
  void theStateIsReadForThePathEmployee() {
    when(guard.require(USER, SHOP, HrPermission.ADD_EDIT_EMPLOYEES)).thenReturn(all());
    PairingStateResponse state = notPaired();
    when(pairingService.pairingState(SHOP, EMPLOYEE)).thenReturn(state);

    assertSame(state, service.state(USER, SHOP, EMPLOYEE));
  }

  // ---- self ----------------------------------------------------------------------------------

  @Test
  void anUnlinkedLoginCannotSeeOrCreateAPairing() {
    when(selfServiceResolver.resolve(SHOP, USER))
        .thenThrow(new BaseException(ErrorCode.HR_NOT_LINKED, "not linked"));

    assertEquals(ErrorCode.HR_NOT_LINKED,
        assertThrows(BaseException.class, () -> service.myState(SHOP, USER)).getErrorCode());
    assertEquals(ErrorCode.HR_NOT_LINKED,
        assertThrows(BaseException.class, () -> service.createMyCode(SHOP, USER)).getErrorCode());
    verifyNoInteractions(pairingService);
    verify(guard, never()).require(anyString(), anyString(), any(HrPermission.class));
  }

  @Test
  void aSelfCodeIsForTheCallersOwnEmployeeAndCreatedBySelf() {
    Employee self = employee(EMPLOYEE);
    when(selfServiceResolver.resolve(SHOP, USER)).thenReturn(self);
    PairingCodeResponse code = new PairingCodeResponse("ABCDEFGH", "qr", Instant.EPOCH);
    when(pairingService.createCode(SHOP, EMPLOYEE, DevicePairingService.SELF)).thenReturn(code);

    assertSame(code, service.createMyCode(SHOP, USER));
    // no HR permission is needed: being linked is the permission (D7)
    verify(guard, never()).require(anyString(), anyString(), any(HrPermission.class));
  }

  @Test
  void theSelfStateIsTheCallersOwn() {
    when(selfServiceResolver.resolve(SHOP, USER)).thenReturn(employee(EMPLOYEE));
    PairingStateResponse state = notPaired();
    when(pairingService.pairingState(SHOP, EMPLOYEE)).thenReturn(state);

    assertSame(state, service.myState(SHOP, USER));
  }
}
