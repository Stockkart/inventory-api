package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.repository.EmployeeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The caller's own employee for {@code /api/v1/hr/me/**} (R1.5, R1.6). Looked up only by the
 * request's {@code shopId} and the caller's {@code userId}; an employee id from a path or body is
 * never used, so a member can never read another employee through self-service.
 */
@Service
public class SelfServiceResolver {

  @Autowired private EmployeeRepository employeeRepository;

  /** @throws BaseException 404 {@code HR_NOT_LINKED} when the login has no employee here */
  public Employee resolve(String shopId, String userId) {
    HrPermissionGuard.requireContext(userId, shopId);
    return employeeRepository.findByShopIdAndUserId(shopId, userId)
        .orElseThrow(() -> new BaseException(
            ErrorCode.HR_NOT_LINKED, ErrorCode.HR_NOT_LINKED.getMessage()));
  }
}
