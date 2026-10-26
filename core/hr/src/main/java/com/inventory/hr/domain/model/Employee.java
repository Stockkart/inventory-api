package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.PayType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A person who works at one shop (R4), with or without a login. Unique {@code (shopId, code)}
 * and, for linked rows, {@code (shopId, userId)}. Contact, pay and payment details are left out of
 * {@code toString} so a stray log line never carries them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_employees")
public class Employee {

  @Id private String id;
  private String shopId;
  /** Unique in the shop; auto {@code EMP-001}, editable. */
  private String code;
  private String name;
  /** null = not given. */
  @ToString.Exclude private String phone;
  /** null = not given (needed for "Email payslip"). */
  @ToString.Exclude private String email;
  /** null = not given. */
  private String designation;
  /** Linked login; null = not linked. */
  private String userId;
  /** yyyy-MM-dd; null = treated as the first day of time. */
  private String joinDate;
  private EmployeeStatus status;
  /** yyyy-MM-dd; null = not exited. Required when EXITED. */
  private String exitDate;
  /** HH:mm; null = use the shop's hours (R4.4). */
  private String workStart;
  /** HH:mm; null = use the shop's hours (R4.4). */
  private String workEnd;
  /**
   * Pay over time; the amount for period P is the latest entry with {@code effectiveFrom <= P}.
   * Empty = no pay set yet (only a user with VIEW_SALARIES may set it).
   */
  @ToString.Exclude private List<SalaryRevision> salaryRevisions = new ArrayList<>();
  /** null = CASH. */
  @ToString.Exclude private HrPaymentMethod paymentMethod;
  /** PII: masked without VIEW_SALARIES and in lists, never logged or audited. */
  @ToString.Exclude private String bankAccountNumber;
  /** PII: hidden without VIEW_SALARIES, never logged or audited. */
  @ToString.Exclude private String ifsc;
  /** PII: masked without VIEW_SALARIES and in lists, never logged or audited. */
  @ToString.Exclude private String upiId;
  /** Optimistic lock; a lost update returns 409 HR_CONCURRENT_UPDATE. */
  @Version private Long revision;
  private Instant createdAt;
  private String createdByUserId;
  private Instant updatedAt;
  private String updatedByUserId;

  /** Pay that applies from {@code effectiveFrom} (YYYY-MM) on (R4.12). */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class SalaryRevision {
    private String effectiveFrom;
    private PayType payType;
    private BigDecimal amount;
    private Instant createdAt;
    private String createdByUserId;
  }
}
