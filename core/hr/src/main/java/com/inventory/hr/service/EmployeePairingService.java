package com.inventory.hr.service;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.UnpairReason;
import com.inventory.hr.rest.dto.response.PairingCodeResponse;
import com.inventory.hr.rest.dto.response.PairingStateResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The Attendance app section of an employee's page, for managers and for the linked employee
 * (R2.1, R2.2, R2.10, R2.12, R2.13, R3.1). Permission checks live here, like every other HR
 * service method; {@link DevicePairingService} itself is permission-free because the phone's own
 * (unsigned) pairing calls use it too.
 *
 * <p>Manager routes need {@code ADD_EDIT_EMPLOYEES}: pairing is editing an employee's record (D7).
 * Self routes resolve the caller's own employee from the request's shop and user, never from the
 * path, and record {@code "self"} as the actor.
 */
@Service
public class EmployeePairingService {

  @Autowired private HrPermissionGuard guard;

  @Autowired private SelfServiceResolver selfServiceResolver;

  @Autowired private DevicePairingService pairingService;

  // ---- manager (ADD_EDIT_EMPLOYEES) ------------------------------------------------------------

  /** {@code GET /hr/employees/{id}/pairing}: Not paired, or Paired with model, date, last punch. */
  public PairingStateResponse state(String userId, String shopId, String employeeId) {
    guard.require(userId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    return pairingService.pairingState(shopId, employeeId);
  }

  /**
   * {@code POST /hr/employees/{id}/pairing-codes}: a fresh 15-minute code; the previous unused
   * one is cancelled (R2.3, R2.4). When the employee already has a phone, the UI has asked
   * "Replace the old phone?" first; the old phone stays paired until the new pairing completes
   * (R2.10), so nothing is unpaired here.
   */
  public PairingCodeResponse createCode(String userId, String shopId, String employeeId) {
    guard.require(userId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    return pairingService.createCode(shopId, employeeId, userId);
  }

  /**
   * {@code POST /hr/employees/{id}/pairing/unpair}: the employee's active phone becomes
   * {@code UNPAIRED} at once with reason {@code MANUAL} and the manager as actor (R2.12, R3.1).
   * Idempotent: with no active phone nothing changes and the state is simply returned.
   */
  public PairingStateResponse unpair(String userId, String shopId, String employeeId) {
    guard.require(userId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    pairingService.unpairActive(shopId, employeeId, UnpairReason.MANUAL, userId);
    return pairingService.pairingState(shopId, employeeId);
  }

  // ---- self (linked employee) ------------------------------------------------------------------

  /** {@code GET /hr/me/pairing}: the caller's own state; 404 {@code HR_NOT_LINKED} otherwise. */
  public PairingStateResponse myState(String shopId, String userId) {
    Employee self = selfServiceResolver.resolve(shopId, userId);
    return pairingService.pairingState(shopId, self.getId());
  }

  /** {@code POST /hr/me/pairing-codes}: a code for the caller's own phone, {@code pairedBy = self} (R2.13). */
  public PairingCodeResponse createMyCode(String shopId, String userId) {
    Employee self = selfServiceResolver.resolve(shopId, userId);
    return pairingService.createCode(shopId, self.getId(), DevicePairingService.SELF);
  }
}
