package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.common.gst.Gstin;
import com.inventory.common.gst.PostalAddress;
import com.inventory.common.util.GstStateCode;
import com.inventory.user.domain.model.enums.CustomerPartyType;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import com.inventory.user.rest.dto.request.UpdateCustomerRequest;
import java.util.Optional;
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
    validateGstin(request.getGstin());
    validateAddress(request.getPostalAddress());
  }

  public void validateUpdateRequest(UpdateCustomerRequest request) {
    if (request == null) {
      throw new ValidationException("Request body is required");
    }
    validateGstin(request.getGstin());
    validateAddress(request.getPostalAddress());
  }

  /**
   * A GSTIN, when given, must pass the offline shape and check-character test. Blank is fine: most
   * walk-in buyers have none. Unlike vendors, a customer is never required to have a state — a sale
   * with no known place is treated as local.
   */
  public void validateGstin(String gstin) {
    if (!StringUtils.hasText(gstin)) {
      return;
    }
    Optional<String> problem = Gstin.problem(gstin);
    if (problem.isPresent()) {
      throw new ValidationException("GSTIN: " + problem.get());
    }
  }

  /** A state code, when given, must be one GST knows. */
  public void validateAddress(PostalAddress address) {
    if (address == null || !StringUtils.hasText(address.getStateCode())) {
      return;
    }
    String code = address.getStateCode().trim();
    if (GstStateCode.format(code).equals(code)) {
      throw new ValidationException("Address: '" + address.getStateCode() + "' is not a GST state code");
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
