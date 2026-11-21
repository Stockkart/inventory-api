package com.inventory.plan.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.rest.dto.request.PlanActiveRequest;
import com.inventory.plan.rest.dto.request.PlanAdminRequest;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PlanAdminValidator {

  private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,29}$");
  private static final Pattern BADGE = Pattern.compile("^[A-Z_]{1,30}$");
  private static final int MAX_NAME = 60;
  private static final int MAX_BEST_FOR = 200;
  private static final int MAX_DISPLAY_ORDER = 1000;

  public void validateCreate(PlanAdminRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (request.getCode() == null || !CODE.matcher(request.getCode()).matches()) {
      throw new ValidationException("Code must be 3-30 characters of A-Z, 0-9 or _, starting with a letter");
    }
    validateContent(null, request);
  }

  public void validateUpdate(String id, PlanAdminRequest request) {
    if (!StringUtils.hasText(id)) {
      throw new ValidationException("Plan ID is required");
    }
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    validateContent(id, request);
  }

  public void validateActive(String id, PlanActiveRequest request) {
    if (!StringUtils.hasText(id)) {
      throw new ValidationException("Plan ID is required");
    }
    if (request == null || request.getActive() == null) {
      throw new ValidationException("active is required");
    }
  }

  private void validateContent(String id, PlanAdminRequest request) {
    if (!StringUtils.hasText(request.getPlanName()) || request.getPlanName().length() > MAX_NAME) {
      throw new ValidationException("Plan name is required, up to " + MAX_NAME + " characters");
    }
    if (request.getArcPrice() == null) {
      throw new ValidationException("Annual price is required");
    }
    nonNegative(request.getArcPrice(), "Annual price");
    nonNegative(request.getPrice(), "Service fee");
    nonNegative(request.getBillingLimit(), "Billing limit");
    nonNegative(request.getBillCountLimit(), "Bill count limit");
    nonNegative(request.getSmsLimit(), "SMS limit");
    nonNegative(request.getWhatsappLimit(), "WhatsApp limit");
    nonNegative(request.getOcrLimit(), "OCR limit");
    if (request.getUserLimit() != null && request.getUserLimit() < 1) {
      throw new ValidationException("User limit must be at least 1");
    }
    if (request.getDisplayOrder() != null
        && (request.getDisplayOrder() < 0 || request.getDisplayOrder() > MAX_DISPLAY_ORDER)) {
      throw new ValidationException("Display order must be 0-" + MAX_DISPLAY_ORDER);
    }
    if (StringUtils.hasText(request.getBadge()) && !BADGE.matcher(request.getBadge()).matches()) {
      throw new ValidationException("Badge must be up to 30 characters of A-Z or _, e.g. MOST_POPULAR");
    }
    if (request.getBestFor() != null && request.getBestFor().length() > MAX_BEST_FOR) {
      throw new ValidationException("Best-for text must be at most " + MAX_BEST_FOR + " characters");
    }
    if (id != null && id.equals(request.getLinkedId())) {
      throw new ValidationException("A plan cannot upsell to itself");
    }
  }

  private static void nonNegative(BigDecimal value, String field) {
    if (value != null && value.signum() < 0) {
      throw new ValidationException(field + " cannot be negative");
    }
  }

  private static void nonNegative(Integer value, String field) {
    if (value != null && value < 0) {
      throw new ValidationException(field + " cannot be negative");
    }
  }
}
