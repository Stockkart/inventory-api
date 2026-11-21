package com.inventory.hr.rest.dto.response;

import com.inventory.hr.domain.model.enums.AdvanceStatus;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An advance with what is still owed (R7.2). {@code recovered} and {@code outstanding} count only
 * lines of FINALISED payroll runs. {@code journalPosted} false = the accounts entry is still
 * pending and is posted again on the next list, record or cancel for the employee.
 */
@Data
@NoArgsConstructor
public class AdvanceResponse {
  private String id;
  private String employeeId;
  private String employeeCode;
  private String employeeName;
  private String date;
  private BigDecimal amount;
  private BigDecimal monthlyDeduction;
  private HrPaymentMethod paidBy;
  private String note;
  private AdvanceStatus status;
  private BigDecimal recovered;
  private BigDecimal outstanding;
  private boolean journalPosted;
  private Instant createdAt;
  private String createdByUserId;
}
