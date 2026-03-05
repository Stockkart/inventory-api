package com.inventory.plan.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.rest.dto.plan.AssignPlanRequest;
import com.inventory.plan.rest.dto.plan.RecordUsageRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PlanValidator {

  public void validateAssignPlanRequest(String shopId, AssignPlanRequest request) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop ID is required");
    }
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(request.getPlanId())) {
      throw new ValidationException("Plan ID is required");
    }
    if (request.getDurationMonths() == null || request.getDurationMonths() < 1) {
      throw new ValidationException("Duration must be at least 1 month");
    }
  }

  public void validateRecordUsageRequest(RecordUsageRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (request.getBillingAmount() == null && request.getBillCount() == null
        && request.getSmsCount() == null && request.getWhatsappCount() == null) {
      throw new ValidationException("At least one usage field must be provided");
    }
    if (request.getBillCount() != null && request.getBillCount() < 0) {
      throw new ValidationException("Bill count cannot be negative");
    }
    if (request.getSmsCount() != null && request.getSmsCount() < 0) {
      throw new ValidationException("SMS count cannot be negative");
    }
    if (request.getWhatsappCount() != null && request.getWhatsappCount() < 0) {
      throw new ValidationException("WhatsApp count cannot be negative");
    }
    if (request.getBillingAmount() != null && request.getBillingAmount().signum() < 0) {
      throw new ValidationException("Billing amount cannot be negative");
    }
  }
}
