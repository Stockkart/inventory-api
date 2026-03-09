package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CustomerValidator {

  public void validateCustomerSearchParams(String phone, String email) {
    if (!StringUtils.hasText(phone) && !StringUtils.hasText(email)) {
      throw new ValidationException("Phone or email is required for search");
    }
    if (StringUtils.hasText(phone) && StringUtils.hasText(email)) {
      throw new ValidationException("Provide either phone or email, not both");
    }
  }
}
