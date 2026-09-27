package com.inventory.plan.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.AddOnBillingType;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.rest.dto.request.AddOnActiveRequest;
import com.inventory.plan.rest.dto.request.AddOnAdminRequest;
import com.inventory.plan.rest.dto.request.AddOnGrantRequest;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AddOnAdminValidator {

  private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,39}$");
  private static final int MAX_NAME = 60;
  private static final int MAX_DESCRIPTION = 200;
  private static final int MAX_DISPLAY_ORDER = 1000;
  private static final int MAX_GRANT_QUANTITY = 1000;
  private static final int MAX_REASON = 500;

  public void validateCreate(AddOnAdminRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (request.getCode() == null || !CODE.matcher(request.getCode()).matches()) {
      throw new ValidationException("Code must be 3-40 characters of A-Z, 0-9 or _, starting with a letter");
    }
    if (request.getGrantType() == null) {
      throw new ValidationException("Grant type is required");
    }
    validateContent(request, request.getGrantType());
  }

  /** {@code grantType} is the stored one: it cannot change after create. */
  public void validateUpdate(String id, AddOnAdminRequest request, AddOnGrantType grantType) {
    if (!StringUtils.hasText(id)) {
      throw new ValidationException("Add-on ID is required");
    }
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (request.getGrantType() != null && request.getGrantType() != grantType) {
      throw new ValidationException("Grant type cannot be changed; create a new add-on instead");
    }
    validateContent(request, grantType);
  }

  public void validateActive(String id, AddOnActiveRequest request) {
    if (!StringUtils.hasText(id)) {
      throw new ValidationException("Add-on ID is required");
    }
    if (request == null || request.getActive() == null) {
      throw new ValidationException("active is required");
    }
  }

  public void validateGrant(AddOnGrantRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(request.getShopId())) {
      throw new ValidationException("Shop ID is required");
    }
    if (!StringUtils.hasText(request.getAddOnCode())) {
      throw new ValidationException("Add-on code is required");
    }
    if (request.getQuantity() != null && (request.getQuantity() < 1 || request.getQuantity() > MAX_GRANT_QUANTITY)) {
      throw new ValidationException("Quantity must be 1-" + MAX_GRANT_QUANTITY);
    }
    if (!StringUtils.hasText(request.getReason()) || request.getReason().length() > MAX_REASON) {
      throw new ValidationException("A reason of up to " + MAX_REASON + " characters is required for a manual grant");
    }
  }

  private void validateContent(AddOnAdminRequest request, AddOnGrantType grantType) {
    if (!StringUtils.hasText(request.getName()) || request.getName().length() > MAX_NAME) {
      throw new ValidationException("Name is required, up to " + MAX_NAME + " characters");
    }
    if (request.getDescription() != null && request.getDescription().length() > MAX_DESCRIPTION) {
      throw new ValidationException("Description must be at most " + MAX_DESCRIPTION + " characters");
    }
    if (request.getPrice() == null || request.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw new ValidationException("Price must be greater than 0");
    }
    if (request.getBillingType() == null) {
      throw new ValidationException("Billing type is required");
    }
    boolean credits = grantType == AddOnGrantType.OCR_CREDITS;
    if (credits != (request.getBillingType() == AddOnBillingType.ONE_TIME)) {
      throw new ValidationException("OCR credits are one-time purchases; every other add-on is annual");
    }
    if (grantType == AddOnGrantType.FEATURE) {
      if (request.getGrantsFeature() == null) {
        throw new ValidationException("A feature add-on must say which feature it grants");
      }
      if (request.isStackable()) {
        throw new ValidationException("A feature add-on cannot be bought more than once");
      }
    } else {
      if (request.getGrantsFeature() != null) {
        throw new ValidationException("Only feature add-ons grant a feature");
      }
      if (request.getGrantsQuantity() == null || request.getGrantsQuantity() < 1) {
        throw new ValidationException("Grant quantity must be at least 1");
      }
    }
    if (request.getMaxQuantity() != null && request.getMaxQuantity() < 1) {
      throw new ValidationException("Max quantity must be at least 1");
    }
    if (request.getDisplayOrder() != null
        && (request.getDisplayOrder() < 0 || request.getDisplayOrder() > MAX_DISPLAY_ORDER)) {
      throw new ValidationException("Display order must be 0-" + MAX_DISPLAY_ORDER);
    }
  }
}
