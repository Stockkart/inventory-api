package com.inventory.hr.rest.dto.request;

import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.PayType;
import java.math.BigDecimal;
import lombok.Data;
import lombok.ToString;

/**
 * Create (POST) or full update (PUT) of an employee. Pay ({@code payType}, {@code salaryAmount},
 * {@code salaryEffectiveFrom}) is read on create only; later changes go through salary revisions.
 * Payment fields need VIEW_SALARIES; without it they must be left null and keep their value.
 */
@Data
public class SaveEmployeeRequest {
  private String name;
  @ToString.Exclude private String phone;
  @ToString.Exclude private String email;
  private String designation;
  /** Blank on create = next {@code EMP-nnn}. Required on update. */
  private String code;
  /** Login to link; null = not linked (on update: unlinks). */
  private String userId;
  /** yyyy-MM-dd. */
  private String joinDate;
  /** HH:mm; both or neither. */
  private String workStart;
  private String workEnd;
  private PayType payType;
  @ToString.Exclude private BigDecimal salaryAmount;
  /** YYYY-MM; null = the join month, else this month. */
  private String salaryEffectiveFrom;
  @ToString.Exclude private HrPaymentMethod paymentMethod;
  @ToString.Exclude private String bankAccountNumber;
  @ToString.Exclude private String ifsc;
  @ToString.Exclude private String upiId;
  /** Revision the client last read; required on update (stale = 409), ignored on create. */
  private Long revision;
}
