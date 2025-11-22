package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.shop.RegisterShopRequest;
import com.inventory.product.rest.dto.shop.ShopApprovalRequest;
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
    if (request.getInitialAdmin() == null) {
      throw new ValidationException("Initial admin information is required");
    }
    if (!StringUtils.hasText(request.getInitialAdmin().getName())) {
      throw new ValidationException("Admin name is required");
    }
    if (!StringUtils.hasText(request.getInitialAdmin().getEmail())) {
      throw new ValidationException("Admin email is required");
    }
    if (!StringUtils.hasText(request.getContactEmail())) {
      throw new ValidationException("Contact email is required");
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
