package com.inventory.hr.service.api;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.user.api.port.HrLinkedEmployeePort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * {@link HrLinkedEmployeePort} for the user module's Users page (worker-app R2.5): the code of the
 * shop's employee a login is linked to. HR sits above user and implements user's port here
 * (module-boundaries B6).
 */
@Component
public class HrLinkedEmployeePortImpl implements HrLinkedEmployeePort {

  private final EmployeeRepository employeeRepository;

  public HrLinkedEmployeePortImpl(EmployeeRepository employeeRepository) {
    this.employeeRepository = employeeRepository;
  }

  @Override
  public String linkedEmployeeCode(String shopId, String userId) {
    if (!StringUtils.hasText(shopId) || !StringUtils.hasText(userId)) {
      return null;
    }
    return employeeRepository.findByShopIdAndUserId(shopId, userId)
        .map(Employee::getCode)
        .orElse(null);
  }
}
