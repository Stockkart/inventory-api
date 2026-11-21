package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.DevicePairing;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;

/**
 * Who a signed Attendance app request comes from, as proven by {@link
 * AttendanceAppAuthInterceptor}: the pairing, and the shop and employee that pairing belongs to.
 *
 * <p>Controllers and services read these from here and never from the request body, the same way
 * {@code HrRequestUtils.requireShopId} reads the Bearer chain's attribute. The whole {@link
 * DevicePairing} is kept so the punch path does not read it a second time (design "Performance":
 * one pairing read per punch).
 *
 * @param pairingId the pairing id ({@code hr_device_pairings._id}), 24 hex characters
 * @param shopId the pairing's shop
 * @param employeeId the pairing's employee
 * @param pairing the {@code ACTIVE} pairing document the envelope was verified against
 */
public record AppRequestContext(
    String pairingId, String shopId, String employeeId, DevicePairing pairing) {

  /** Request attribute the interceptor stores the context under. */
  public static final String ATTRIBUTE = AppRequestContext.class.getName();

  public AppRequestContext {
    Objects.requireNonNull(pairingId, "pairingId");
    Objects.requireNonNull(shopId, "shopId");
    Objects.requireNonNull(employeeId, "employeeId");
    Objects.requireNonNull(pairing, "pairing");
  }

  /** Builds the context for a verified pairing. */
  public static AppRequestContext of(DevicePairing pairing) {
    return new AppRequestContext(
        pairing.getId(), pairing.getShopId(), pairing.getEmployeeId(), pairing);
  }

  /**
   * The verified context of this request.
   *
   * @throws BaseException {@code HR_APP_NOT_PAIRED} when the request did not pass the interceptor
   *     (a handler reachable without the envelope must never call this)
   */
  public static AppRequestContext require(HttpServletRequest request) {
    Object value = request.getAttribute(ATTRIBUTE);
    if (value instanceof AppRequestContext context) {
      return context;
    }
    throw new BaseException(ErrorCode.HR_APP_NOT_PAIRED, ErrorCode.HR_APP_NOT_PAIRED.getMessage());
  }
}
