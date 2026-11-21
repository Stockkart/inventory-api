package com.inventory.plan.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.VoucherTarget;
import com.inventory.plan.domain.model.VoucherType;
import com.inventory.plan.rest.dto.request.PlanVoucherPreviewRequest;
import com.inventory.plan.rest.dto.request.VoucherActiveRequest;
import com.inventory.plan.rest.dto.request.VoucherGenerateRequest;
import com.inventory.plan.rest.dto.request.VoucherUpdateRequest;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class VoucherAdminValidator {

  public static final int MAX_BATCH = 500;
  public static final int MAX_PAGE_SIZE = 500;
  private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9-]{3,29}$");
  private static final Pattern PREFIX = Pattern.compile("^[A-Z0-9]{1,8}$");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final int MAX_NOTE = 500;

  public void validateGenerate(VoucherGenerateRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    int count = request.getCount() != null ? request.getCount() : 1;
    if (count < 1 || count > MAX_BATCH) {
      throw new ValidationException("Count must be 1-" + MAX_BATCH);
    }
    if (StringUtils.hasText(request.getCode())) {
      if (count != 1) {
        throw new ValidationException("A chosen code creates one voucher; leave count empty");
      }
      if (!CODE.matcher(request.getCode().trim().toUpperCase()).matches()) {
        throw new ValidationException("Code must be 4-30 characters of A-Z, 0-9 or -");
      }
    }
    if (StringUtils.hasText(request.getPrefix()) && !PREFIX.matcher(request.getPrefix()).matches()) {
      throw new ValidationException("Prefix must be 1-8 characters of A-Z or 0-9");
    }
    if (request.getTarget() == VoucherTarget.PLAN) {
      validatePlanTarget(request);
    } else {
      if (!StringUtils.hasText(request.getAddOnCode())) {
        throw new ValidationException("Add-on code is required; vouchers apply to add-ons only");
      }
      if (StringUtils.hasText(request.getPlanCode())) {
        throw new ValidationException("An add-on voucher cannot name a plan");
      }
    }
    validateDiscount(request.getType(), request.getValue());
    if (request.getQuantity() != null && request.getQuantity() < 1) {
      throw new ValidationException("Quantity must be at least 1");
    }
    validateCap(request.getMaxRedemptions());
    if (request.getValidFrom() != null && request.getValidTo() != null
        && !request.getValidTo().isAfter(request.getValidFrom())) {
      throw new ValidationException("Valid-to must be after valid-from");
    }
    validateNote(request.getNote());
  }

  /** Shape check for the plan-voucher preview: the same plan and discount rules as generate. */
  public void validatePlanPreview(PlanVoucherPreviewRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    validatePlanCode(request.getPlanCode(), request.getType());
    validateDiscount(request.getType(), request.getValue());
  }

  private static void validateDiscount(VoucherType type, BigDecimal value) {
    if (type == null) {
      throw new ValidationException("Voucher type is required");
    }
    switch (type) {
      case FREE_ADDON -> {
        if (value != null) {
          throw new ValidationException("A free add-on voucher has no value");
        }
      }
      case PERCENT_OFF -> {
        if (value == null || value.signum() <= 0 || value.compareTo(HUNDRED) > 0) {
          throw new ValidationException("Percent off must be more than 0 and at most 100");
        }
      }
      case FLAT_OFF -> {
        if (value == null || value.signum() <= 0) {
          throw new ValidationException("Flat discount must be greater than 0");
        }
        if (value.stripTrailingZeros().scale() > 2) {
          throw new ValidationException("Flat discount can have at most 2 decimal places");
        }
      }
    }
  }

  private static void validatePlanCode(String planCode, VoucherType type) {
    if (!StringUtils.hasText(planCode)) {
      throw new ValidationException("Plan code is required for a plan voucher");
    }
    if (type == VoucherType.FREE_ADDON) {
      throw new ValidationException("Plan vouchers are percent or flat off; a plan cannot be free");
    }
  }

  private static void validatePlanTarget(VoucherGenerateRequest request) {
    if (!StringUtils.hasText(request.getPlanCode())) {
      throw new ValidationException("Plan code is required for a plan voucher");
    }
    if (StringUtils.hasText(request.getAddOnCode())) {
      throw new ValidationException("A plan voucher cannot name an add-on");
    }
    validatePlanCode(request.getPlanCode(), request.getType());
    if (request.getQuantity() != null) {
      throw new ValidationException("Quantity does not apply to a plan voucher");
    }
  }

  public void validateUpdate(String id, VoucherUpdateRequest request) {
    if (!StringUtils.hasText(id)) {
      throw new ValidationException("Voucher ID is required");
    }
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    validateCap(request.getMaxRedemptions());
    validateNote(request.getNote());
  }

  public void validatePage(Integer page, Integer size) {
    if (page != null && page < 0) {
      throw new ValidationException("Page must be 0 or more");
    }
    if (size != null && (size < 1 || size > MAX_PAGE_SIZE)) {
      throw new ValidationException("Page size must be 1-" + MAX_PAGE_SIZE);
    }
  }

  public void validateActive(String id, VoucherActiveRequest request) {
    if (!StringUtils.hasText(id)) {
      throw new ValidationException("Voucher ID is required");
    }
    if (request == null || request.getActive() == null) {
      throw new ValidationException("active is required");
    }
  }

  private static void validateCap(Integer maxRedemptions) {
    if (maxRedemptions != null && maxRedemptions < 1) {
      throw new ValidationException("Max redemptions must be at least 1");
    }
  }

  private static void validateNote(String note) {
    if (note != null && note.length() > MAX_NOTE) {
      throw new ValidationException("Note must be at most " + MAX_NOTE + " characters");
    }
  }
}
