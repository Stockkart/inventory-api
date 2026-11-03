package com.inventory.hr.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.rest.dto.request.RecordAdvanceRequest;
import com.inventory.hr.utils.HrDateUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Advance input (R7.1): amount &gt; 0 with at most 2 decimals and at most ₹1 crore, 0 &lt; monthly
 * deduction ≤ amount, paid by CASH, BANK or UPI, a strict date that is not in the future, short
 * note. Messages never repeat the amount.
 */
@Component
public class AdvanceValidator {

  static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");
  public static final int MAX_TEXT_LENGTH = 500;

  /** The parsed payment method of a valid request. */
  public HrPaymentMethod validate(RecordAdvanceRequest request, LocalDate today) {
    if (request == null) {
      throw new ValidationException("Advance details are required");
    }
    if (!StringUtils.hasText(request.getEmployeeId())) {
      throw new ValidationException("employeeId is required");
    }
    LocalDate date = HrDateUtils.parseDate(request.getDate());
    if (date == null) {
      throw new ValidationException("date must be a date like 2025-04-30");
    }
    if (date.isAfter(today)) {
      throw new ValidationException("An advance cannot be dated in the future");
    }
    requireMoney("amount", request.getAmount());
    requireMoney("monthlyDeduction", request.getMonthlyDeduction());
    if (request.getMonthlyDeduction().compareTo(request.getAmount()) > 0) {
      throw new ValidationException("The monthly deduction cannot be more than the advance");
    }
    HrPaymentMethod paidBy = parsePaidBy(request.getPaidBy());
    validateText("note", request.getNote());
    return paidBy;
  }

  /** Cancel needs a reason (audited). */
  public String requireReason(String reason) {
    if (!StringUtils.hasText(reason)) {
      throw new ValidationException("A reason is required");
    }
    validateText("reason", reason);
    return reason.trim();
  }

  static HrPaymentMethod parsePaidBy(String value) {
    if (!StringUtils.hasText(value)) {
      throw new ValidationException("paidBy is required: CASH, BANK or UPI");
    }
    try {
      return HrPaymentMethod.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("paidBy must be CASH, BANK or UPI");
    }
  }

  private static void requireMoney(String field, BigDecimal value) {
    if (value == null || value.signum() <= 0) {
      throw new ValidationException(field + " must be more than zero");
    }
    if (value.stripTrailingZeros().scale() > 2) {
      throw new ValidationException(field + " may have at most 2 decimals (paise)");
    }
    if (value.compareTo(MAX_AMOUNT) > 0) {
      throw new ValidationException(field + " is too large");
    }
  }

  private static void validateText(String field, String value) {
    if (value != null && value.length() > MAX_TEXT_LENGTH) {
      throw new ValidationException(field + " may have at most " + MAX_TEXT_LENGTH
          + " characters");
    }
  }
}
