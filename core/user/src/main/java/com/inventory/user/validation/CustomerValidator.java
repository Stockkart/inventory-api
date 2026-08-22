package com.inventory.user.validation;

import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import com.inventory.user.rest.dto.request.UpdateCustomerRequest;
import com.inventory.common.exception.ValidationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CustomerValidator {

  public void validateShopId(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("User not authenticated or shop not found");
    }
  }

  public void validateCustomerId(String customerId) {
    if (!StringUtils.hasText(customerId) || customerId.trim().isEmpty()) {
      throw new ValidationException("Customer ID is required");
    }
  }

  public void validateCreateRequest(CreateCustomerRequest request) {
    if (request == null) {
      throw new ValidationException("Request body is required");
    }
    if (!StringUtils.hasText(request.getName()) || request.getName().trim().isEmpty()) {
      throw new ValidationException("Customer name is required");
    }
  }

  public void validateUpdateRequest(UpdateCustomerRequest request) {
    if (request == null) {
      throw new ValidationException("Request body is required");
    }
  }

  public void validateCustomerSearchParams(String phone, String email, String name) {
    int given = 0;
    if (StringUtils.hasText(phone)) given++;
    if (StringUtils.hasText(email)) given++;
    if (StringUtils.hasText(name)) given++;
    if (given == 0) {
      throw new ValidationException("Phone, email or name is required for search");
    }
    if (given > 1) {
      throw new ValidationException("Provide one of phone, email or name, not several");
    }
  }

  public void validateListParams(Integer page, Integer limit) {
    if (page != null && page < 0) {
      throw new ValidationException("Page must be >= 0");
    }
    if (limit != null && (limit <= 0 || limit > 100)) {
      throw new ValidationException("Limit must be between 1 and 100");
    }
  }
}
