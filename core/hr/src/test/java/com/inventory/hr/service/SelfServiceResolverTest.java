package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static com.inventory.hr.HrTestFixtures.employee;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.repository.EmployeeRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class SelfServiceResolverTest {

  @Mock private EmployeeRepository employeeRepository;
  @InjectMocks private SelfServiceResolver resolver;

  @Test
  void theCallersOwnEmployeeIsFoundByShopAndUser() {
    Employee own = employee("e1");
    when(employeeRepository.findByShopIdAndUserId(SHOP, USER)).thenReturn(Optional.of(own));

    assertSame(own, resolver.resolve(SHOP, USER));
  }

  @Test
  void anUnlinkedLoginIs404NotLinked() {
    when(employeeRepository.findByShopIdAndUserId(SHOP, USER)).thenReturn(Optional.empty());

    BaseException e = assertThrows(BaseException.class, () -> resolver.resolve(SHOP, USER));
    assertEquals(ErrorCode.HR_NOT_LINKED, e.getErrorCode());
    assertEquals(HttpStatus.NOT_FOUND, e.getErrorCode().getHttpStatus());
  }
}
