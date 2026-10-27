package com.inventory.hr.domain.model;

import com.inventory.hr.domain.model.enums.LeaveSystemDefault;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** A shop's leave type (R6.1); unique {@code (shopId, name)}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_leave_types")
public class LeaveType {

  @Id private String id;
  private String shopId;
  private String name;
  private Boolean paid;
  /** Days per leave year; null = no limit. */
  private BigDecimal yearlyQuotaDays;
  /** null = true. Types in use are deactivated, never deleted. */
  private Boolean active;
  /** Which seeded default this is; null = made by the shop. */
  private LeaveSystemDefault systemDefault;
  private Instant createdAt;
  private Instant updatedAt;
  private String updatedByUserId;
}
