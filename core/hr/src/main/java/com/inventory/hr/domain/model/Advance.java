package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.AdvanceStatus;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Money given ahead of salary (R7). Outstanding = amount − recoveries on lines of FINALISED runs;
 * it is derived, never stored (D5).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_advances")
public class Advance {

  @Id private String id;
  private String shopId;
  private String employeeId;
  /** yyyy-MM-dd. */
  private String date;
  @ToString.Exclude private BigDecimal amount;
  @ToString.Exclude private BigDecimal monthlyDeduction;
  private HrPaymentMethod paidBy;
  /** null = none. */
  private String note;
  private AdvanceStatus status;
  /** STAFF_ADVANCE journal; null = post not done yet (re-posted idempotently). */
  private String journalEntryId;
  /** null = not cancelled. */
  private Instant cancelledAt;
  private String cancelledByUserId;
  /** null = not cancelled. */
  private String cancelReason;
  /**
   * true once a cancel has reversed the STAFF_ADVANCE journal (or found none to reverse); null on
   * a cancelled advance = reversal still to do (finished by the next list or cancel).
   */
  private Boolean journalReversed;
  private Instant createdAt;
  private String createdByUserId;
}
