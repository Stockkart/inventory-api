package com.inventory.hr.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.rest.dto.request.SaveHolidayRequest;
import com.inventory.hr.utils.HrDateUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class HolidayValidator {

  static final int MAX_NAME = 100;

  public void validate(SaveHolidayRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!HrDateUtils.isDate(request.getDate())) {
      throw new ValidationException("Holiday date must be a date like 2025-08-15");
    }
    validateYear(HrDateUtils.parseDate(request.getDate()).getYear());
    if (!StringUtils.hasText(request.getName())) {
      throw new ValidationException("Holiday name is required");
    }
    if (request.getName().trim().length() > MAX_NAME) {
      throw new ValidationException("Holiday name may have at most " + MAX_NAME + " characters");
    }
  }

  public void validateYear(Integer year) {
    if (year != null && (year < 2000 || year > 2100)) {
      throw new ValidationException("Year must be between 2000 and 2100");
    }
  }
}
