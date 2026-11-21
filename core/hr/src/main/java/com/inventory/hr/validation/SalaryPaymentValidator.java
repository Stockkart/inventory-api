package com.inventory.hr.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.rest.dto.request.RecordSalaryPaymentsRequest;
import com.inventory.hr.utils.HrDateUtils;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Salary payment input (R9.1, design caps: ≤ 50 lines per call). */
@Component
public class SalaryPaymentValidator {

  public static final int MAX_LINES = 50;
  static final int MAX_REFERENCE = 100;
  static final int MAX_REASON = 500;

  /** The checked request: distinct ids (empty for {@code all}), date, method, reference. */
  public record Checked(List<String> lineIds, boolean all, LocalDate date, HrPaymentMethod method,
      String reference) {}

  public Checked validate(RecordSalaryPaymentsRequest request, LocalDate today) {
    if (request == null) {
      throw new ValidationException("Choose who to pay");
    }
    boolean all = Boolean.TRUE.equals(request.getAll());
    Set<String> ids = new LinkedHashSet<>();
    if (request.getLineIds() != null) {
      for (String id : request.getLineIds()) {
        if (!StringUtils.hasText(id)) {
          throw new ValidationException("lineIds must not contain empty values");
        }
        ids.add(id.trim());
      }
    }
    if (all && !ids.isEmpty()) {
      throw new ValidationException("Send either lineIds or all, not both");
    }
    if (!all && ids.isEmpty()) {
      throw new ValidationException("Choose who to pay");
    }
    if (ids.size() > MAX_LINES) {
      throw new ValidationException("Pay at most " + MAX_LINES + " employees at a time");
    }
    LocalDate date = HrDateUtils.parseDate(request.getDate());
    if (date == null) {
      throw new ValidationException("date must be a date like 2025-05-01");
    }
    if (date.isAfter(today)) {
      throw new ValidationException("The payment date cannot be in the future");
    }
    HrPaymentMethod method = parseMethod(request.getMethod());
    String reference = StringUtils.hasText(request.getReference())
        ? request.getReference().trim() : null;
    if (reference != null && reference.length() > MAX_REFERENCE) {
      throw new ValidationException("reference must be at most " + MAX_REFERENCE + " characters");
    }
    return new Checked(List.copyOf(ids), all, date, method, reference);
  }

  /** Undo reason: optional, ≤ 500 characters. */
  public String reason(String reason) {
    if (!StringUtils.hasText(reason)) {
      return null;
    }
    String trimmed = reason.trim();
    if (trimmed.length() > MAX_REASON) {
      throw new ValidationException("reason must be at most " + MAX_REASON + " characters");
    }
    return trimmed;
  }

  private static HrPaymentMethod parseMethod(String method) {
    if (!StringUtils.hasText(method)) {
      throw new ValidationException("method must be CASH, BANK or UPI");
    }
    try {
      return HrPaymentMethod.valueOf(method.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("method must be CASH, BANK or UPI");
    }
  }
}
