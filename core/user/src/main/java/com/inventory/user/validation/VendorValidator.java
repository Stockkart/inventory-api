package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.user.rest.dto.vendor.CreateVendorRequest;
import com.inventory.user.rest.dto.vendor.SearchVendorRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class VendorValidator {

  public void validateCreateRequest(CreateVendorRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(request.getName())) {
      throw new ValidationException("Vendor name is required");
    }
    // Either phone or email must be provided
    boolean hasPhone = StringUtils.hasText(request.getContactPhone());
    boolean hasEmail = StringUtils.hasText(request.getContactEmail());
    if (!hasPhone && !hasEmail) {
      throw new ValidationException("Either vendor phone or vendor email is required");
    }
  }

  public void validateSearchRequest(SearchVendorRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    // Query must be provided
    if (!StringUtils.hasText(request.getQuery())) {
      throw new ValidationException("Search query is required");
    }
  }
}

