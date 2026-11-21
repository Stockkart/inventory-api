package com.inventory.hr.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.enums.ManualItemKind;
import com.inventory.hr.rest.dto.request.SavePayrollAdjustmentsRequest;
import com.inventory.hr.utils.HrDateUtils;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Payroll input: the run period, manual items and the advance override of a line (R7.4, R8.9),
 * and the rule that adjustments leave net pay ≥ 0. Messages never carry amounts.
 */
@Component
public class PayrollAdjustmentValidator {

  static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");
  public static final int MAX_ITEMS = 20;
  public static final int MAX_NOTE_LENGTH = 100;
  public static final int MAX_REASON_LENGTH = 500;

  /** A strict YYYY-MM that is not after {@code current}. */
  public YearMonth requirePeriod(String period, YearMonth current) {
    YearMonth month = HrDateUtils.parsePeriod(period);
    if (month == null) {
      throw new ValidationException("period must be a month like 2025-04");
    }
    if (month.isAfter(current)) {
      throw new ValidationException("Payroll cannot be run for a future month");
    }
    return month;
  }

  /** The manual items as stored; amounts &gt; 0 with at most 2 decimals. */
  public List<PayrollLine.ManualItem> validate(SavePayrollAdjustmentsRequest request) {
    if (request == null) {
      throw new ValidationException("Adjustments are required");
    }
    List<PayrollLine.ManualItem> items = new ArrayList<>();
    List<SavePayrollAdjustmentsRequest.Item> in =
        request.getManualItems() != null ? request.getManualItems() : List.of();
    if (in.size() > MAX_ITEMS) {
      throw new ValidationException("A line may have at most " + MAX_ITEMS + " manual items");
    }
    for (SavePayrollAdjustmentsRequest.Item item : in) {
      if (item == null) {
        throw new ValidationException("A manual item is empty");
      }
      ManualItemKind kind = parseKind(item.getKind());
      requireMoney("amount", item.getAmount(), false);
      String note = StringUtils.hasText(item.getNote()) ? item.getNote().trim() : null;
      if (note != null && note.length() > MAX_NOTE_LENGTH) {
        throw new ValidationException("A note may have at most " + MAX_NOTE_LENGTH
            + " characters");
      }
      items.add(new PayrollLine.ManualItem(kind, note, item.getAmount()));
    }
    if (request.getAdvanceOverride() != null) {
      requireMoney("advanceOverride", request.getAdvanceOverride(), true);
    }
    return items;
  }

  /** Adjustments may not push net pay below zero (R7.3, task 8.4). */
  public void requireNonNegativeNet(PayrollLine computed) {
    if (computed.getNetBeforeRounding() != null && computed.getNetBeforeRounding().signum() < 0) {
      throw new ValidationException(
          "The deductions are more than this employee's pay for the month");
    }
  }

  /** Reopen needs a reason (R8.14, audited). */
  public String requireReason(String reason) {
    if (!StringUtils.hasText(reason)) {
      throw new ValidationException("A reason is required");
    }
    if (reason.length() > MAX_REASON_LENGTH) {
      throw new ValidationException("The reason may have at most " + MAX_REASON_LENGTH
          + " characters");
    }
    return reason.trim();
  }

  static ManualItemKind parseKind(String value) {
    if (!StringUtils.hasText(value)) {
      throw new ValidationException("kind is required: BONUS or OTHER_DEDUCTION");
    }
    try {
      return ManualItemKind.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("kind must be BONUS or OTHER_DEDUCTION");
    }
  }

  private static void requireMoney(String field, BigDecimal value, boolean zeroAllowed) {
    if (value == null || value.signum() < 0 || (!zeroAllowed && value.signum() == 0)) {
      throw new ValidationException(field + (zeroAllowed ? " cannot be negative"
          : " must be more than zero"));
    }
    if (value.stripTrailingZeros().scale() > 2) {
      throw new ValidationException(field + " may have at most 2 decimals (paise)");
    }
    if (value.compareTo(MAX_AMOUNT) > 0) {
      throw new ValidationException(field + " is too large");
    }
  }
}
