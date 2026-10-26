package com.inventory.hr.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.rest.dto.request.AddSalaryRevisionRequest;
import com.inventory.hr.rest.dto.request.SaveEmployeeRequest;
import com.inventory.hr.utils.HrDateUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Employee input checks. Messages never repeat an account number, IFSC or UPI id, because
 * validation messages are logged.
 */
@Component
public class EmployeeValidator {

  public static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
  public static final Pattern UPI = Pattern.compile("^[\\w.\\-]{2,}@[a-zA-Z]{2,}$");
  static final Pattern ACCOUNT = Pattern.compile("^[0-9]{6,18}$");
  static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 \\-]{5,18}$");
  static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9\\-_/]{0,19}$");
  static final int MAX_NAME = 100;
  static final int MAX_DESIGNATION = 50;
  static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");

  /**
   * Fields of a create or update. Pay fields are checked only on create when sent (later changes
   * go through salary revisions); payment details whenever they are sent.
   */
  public void validate(SaveEmployeeRequest request, boolean creating) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(request.getName())) {
      throw new ValidationException("Employee name is required");
    }
    if (request.getName().trim().length() > MAX_NAME) {
      throw new ValidationException("Employee name may have at most " + MAX_NAME + " characters");
    }
    if (StringUtils.hasText(request.getPhone()) && !PHONE.matcher(request.getPhone().trim()).matches()) {
      throw new ValidationException("Phone must be a phone number");
    }
    if (StringUtils.hasText(request.getEmail()) && !EMAIL.matcher(request.getEmail().trim()).matches()) {
      throw new ValidationException("Email must be an email address");
    }
    if (StringUtils.hasText(request.getDesignation())
        && request.getDesignation().trim().length() > MAX_DESIGNATION) {
      throw new ValidationException(
          "Designation may have at most " + MAX_DESIGNATION + " characters");
    }
    if (StringUtils.hasText(request.getCode())) {
      if (!CODE.matcher(request.getCode().trim()).matches()) {
        throw new ValidationException(
            "Employee code may have up to 20 letters, digits, '-', '_' or '/'");
      }
    } else if (!creating) {
      throw new ValidationException("Employee code is required");
    }
    if (request.getJoinDate() != null && !HrDateUtils.isDate(request.getJoinDate())) {
      throw new ValidationException("Join date must be a date like 2025-04-01");
    }
    validateHours(request.getWorkStart(), request.getWorkEnd());
    if (creating && (request.getPayType() != null || request.getSalaryAmount() != null)) {
      validatePay(request.getPayType(), request.getSalaryAmount());
      if (request.getSalaryEffectiveFrom() != null
          && !HrDateUtils.isPeriod(request.getSalaryEffectiveFrom())) {
        throw new ValidationException("Salary start month must look like 2025-04");
      }
    }
    validatePaymentDetails(request);
  }

  public void validateRevision(AddSalaryRevisionRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!HrDateUtils.isPeriod(request.getEffectiveFrom())) {
      throw new ValidationException("Salary start month must look like 2025-04");
    }
    validatePay(request.getPayType(), request.getAmount());
  }

  /** Exit date is required and may not be before the join date. */
  public void validateExit(String exitDate, String joinDate) {
    LocalDate exit = HrDateUtils.parseDate(exitDate);
    if (exit == null) {
      throw new ValidationException("Exit date must be a date like 2025-04-30");
    }
    LocalDate join = HrDateUtils.parseDate(joinDate);
    if (join != null && exit.isBefore(join)) {
      throw new ValidationException("Exit date cannot be before the join date");
    }
  }

  private static void validatePay(Object payType, BigDecimal amount) {
    if (payType == null) {
      throw new ValidationException("Pay type is required (monthly salary or daily wage)");
    }
    if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0
        || amount.stripTrailingZeros().scale() > 2) {
      throw new ValidationException("Salary must be more than 0, in rupees and paise");
    }
  }

  private static void validateHours(String start, String end) {
    if (start == null && end == null) {
      return;
    }
    if (!HrDateUtils.isTime(start) || !HrDateUtils.isTime(end)) {
      throw new ValidationException("Own working hours need both a start and an end like 09:00");
    }
    if (!HrDateUtils.parseTime(start).isBefore(HrDateUtils.parseTime(end))) {
      throw new ValidationException("Work start must be before work end (no overnight hours)");
    }
  }

  private static void validatePaymentDetails(SaveEmployeeRequest request) {
    if (StringUtils.hasText(request.getBankAccountNumber())
        && !ACCOUNT.matcher(request.getBankAccountNumber().trim()).matches()) {
      throw new ValidationException("Bank account number must have 6 to 18 digits");
    }
    if (StringUtils.hasText(request.getIfsc())
        && !IFSC.matcher(request.getIfsc().trim().toUpperCase()).matches()) {
      throw new ValidationException("IFSC must have 11 characters like ABCD0123456");
    }
    if (StringUtils.hasText(request.getUpiId())
        && !UPI.matcher(request.getUpiId().trim()).matches()) {
      throw new ValidationException("UPI ID must look like name@bank");
    }
    if (request.getPaymentMethod() == HrPaymentMethod.BANK
        && (!StringUtils.hasText(request.getBankAccountNumber())
            || !StringUtils.hasText(request.getIfsc()))) {
      throw new ValidationException("Bank payment needs the account number and IFSC");
    }
    if (request.getPaymentMethod() == HrPaymentMethod.UPI
        && !StringUtils.hasText(request.getUpiId())) {
      throw new ValidationException("UPI payment needs the UPI ID");
    }
  }
}
