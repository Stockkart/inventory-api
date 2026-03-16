package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.RegisterShopRequest;
import com.inventory.product.rest.dto.request.ShopApprovalRequest;
import com.inventory.product.rest.dto.request.UpdateShopRequest;
import com.inventory.product.rest.dto.response.LocationDto;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ShopValidator {

  public void validateRegisterRequest(RegisterShopRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(request.getName())) {
      throw new ValidationException("Shop name is required");
    }
    if (!StringUtils.hasText(request.getBusinessId())) {
      throw new ValidationException("Business ID is required");
    }
    if (request.getLocation() == null) {
      throw new ValidationException("Location is required");
    }
    if (!StringUtils.hasText(request.getLocation().getPrimaryAddress())) {
      throw new ValidationException("Primary address is required");
    }
    if (!StringUtils.hasText(request.getLocation().getState())) {
      throw new ValidationException("State is required");
    }
    if (!StringUtils.hasText(request.getLocation().getCity())) {
      throw new ValidationException("City is required");
    }
    if (!StringUtils.hasText(request.getLocation().getPin())) {
      throw new ValidationException("PIN code is required");
    }
    if (!StringUtils.hasText(request.getContactEmail())) {
      throw new ValidationException("Contact email is required");
    }
    if (!StringUtils.hasText(request.getContactPhone())) {
      throw new ValidationException("Contact phone is required");
    }
  }

  public void validateUpdateRequest(UpdateShopRequest request) {
    if (request == null) {
      throw new ValidationException("Update request cannot be null");
    }
    // If location is provided, validate required location fields
    LocationDto loc = request.getLocation();
    if (loc != null) {
      if (!StringUtils.hasText(loc.getPrimaryAddress())) {
        throw new ValidationException("Primary address is required when updating location");
      }
      if (!StringUtils.hasText(loc.getState())) {
        throw new ValidationException("State is required when updating location");
      }
      if (!StringUtils.hasText(loc.getCity())) {
        throw new ValidationException("City is required when updating location");
      }
      if (!StringUtils.hasText(loc.getPin())) {
        throw new ValidationException("PIN code is required when updating location");
      }
      if (!StringUtils.hasText(loc.getCountry())) {
        throw new ValidationException("Country is required when updating location");
      }
    }
  }

  public void validateShopAccess(boolean hasAccess) {
    if (!hasAccess) {
      throw new ValidationException("You do not have access to this shop");
    }
  }

  public void validateApprovalRequest(String shopId, ShopApprovalRequest request) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }
    if (request == null) {
      throw new ValidationException("Approval request cannot be null");
    }
    if (request.isApprove() && (request.getUserLimit() == null || request.getUserLimit() <= 0)) {
      throw new ValidationException("User limit must be greater than 0 when approving a shop");
    }
  }
}
