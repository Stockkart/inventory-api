package com.inventory.user.validation;

import com.inventory.user.domain.model.enums.CustomerPartyType;
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
    if (!hasUniqueIdentifier(request.getPhone(), request.getEmail(), request.getGstin(), request.getPan(), request.getDlNo())) {
      throw new ValidationException(
          "A unique customer requires phone, email, GSTIN, PAN, or DL. Name and address alone use the general customer.");
    }
    CustomerPartyType partyType =
        request.getPartyType() != null ? request.getPartyType() : CustomerPartyType.CONSUMER;
    if (partyType != CustomerPartyType.CONSUMER
        && !hasUniqueIdentifier(request.getPhone(), request.getEmail(), request.getGstin(), request.getPan(), request.getDlNo())) {
      throw new ValidationException(
          "Retailer, distributor, and wholesaler customers require phone, email, GSTIN, PAN, or DL");
    }
  }

  public void validateUpdateRequest(UpdateCustomerRequest request) {
    if (request == null) {
      throw new ValidationException("Request body is required");
    }
  }

  public void validateCustomerSearchParams(String phone, String email) {
    if (!StringUtils.hasText(phone) && !StringUtils.hasText(email)) {
      throw new ValidationException("Phone or email is required for search");
    }
    if (StringUtils.hasText(phone) && StringUtils.hasText(email)) {
      throw new ValidationException("Provide either phone or email, not both");
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

  public static boolean hasUniqueIdentifier(
      String phone, String email, String gstin, String pan, String dlNo) {
    return StringUtils.hasText(phone)
        || StringUtils.hasText(email)
        || StringUtils.hasText(gstin)
        || StringUtils.hasText(pan)
        || StringUtils.hasText(dlNo);
  }
}
