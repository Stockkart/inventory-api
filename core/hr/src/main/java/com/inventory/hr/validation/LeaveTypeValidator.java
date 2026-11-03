package com.inventory.hr.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.rest.dto.request.SaveLeaveTypeRequest;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LeaveTypeValidator {

  static final int MAX_NAME = 50;
  static final BigDecimal MAX_QUOTA = new BigDecimal("366");
  private static final BigDecimal HALF = new BigDecimal("0.5");

  public void validate(SaveLeaveTypeRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(request.getName())) {
      throw new ValidationException("Leave type name is required");
    }
    if (request.getName().trim().length() > MAX_NAME) {
      throw new ValidationException("Leave type name may have at most " + MAX_NAME + " characters");
    }
    if (request.getPaid() == null) {
      throw new ValidationException("Say whether the leave type is paid");
    }
    BigDecimal quota = request.getYearlyQuotaDays();
    if (quota != null) {
      // whole or half days only, since a leave day is never smaller than half a day
      if (quota.signum() < 0 || quota.compareTo(MAX_QUOTA) > 0
          || quota.remainder(HALF).signum() != 0) {
        throw new ValidationException(
            "Yearly quota must be whole or half days between 0 and " + MAX_QUOTA);
      }
    }
  }
}
