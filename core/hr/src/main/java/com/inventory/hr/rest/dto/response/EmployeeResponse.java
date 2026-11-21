package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.PayType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * An employee as the caller may see it. Without VIEW_SALARIES (and in every list row) the pay and
 * payment fields are masked by {@code EmployeeMapper}; {@code payHidden = true} tells the UI that
 * pay was withheld rather than not set.
 */
@Data
@NoArgsConstructor
public class EmployeeResponse {
  private String id;
  private String code;
  private String name;
  @ToString.Exclude private String phone;
  @ToString.Exclude private String email;
  private String designation;
  private String userId;
  private String joinDate;
  private EmployeeStatus status;
  private String exitDate;
  private String workStart;
  private String workEnd;
  /** Pay that applies this month; null when hidden or not set. */
  private PayType payType;
  @ToString.Exclude private BigDecimal salaryAmount;
  /** null when hidden. */
  @ToString.Exclude private List<SalaryRevisionResponse> salaryRevisions;
  /** null when hidden. */
  @ToString.Exclude private HrPaymentMethod paymentMethod;
  /** Full, or {@code XXXXXX1234} when masked. */
  @ToString.Exclude private String bankAccountNumber;
  /** null when hidden. */
  @ToString.Exclude private String ifsc;
  /** Full, or {@code ra***@okaxis} when masked. */
  @ToString.Exclude private String upiId;
  private boolean payHidden;
  private Long revision;
  private Instant createdAt;
  private Instant updatedAt;

  @Data
  @NoArgsConstructor
  public static class SalaryRevisionResponse {
    private String effectiveFrom;
    private PayType payType;
    @ToString.Exclude private BigDecimal amount;
  }
}
