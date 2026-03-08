package com.inventory.plan.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Records plan payment transactions when a shop subscribes to a plan.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "plan_transactions")
public class PlanTransaction {

  @Id
  private String id;
  private String shopId;
  private String planId;
  private String planName;
  /** Amount paid (e.g. arcPrice or price). */
  private BigDecimal amount;
  /** Duration in months (1 = monthly). */
  private Integer durationMonths;
  /** Payment method: CARD, UPI, NET_BANKING, etc. */
  private String paymentMethod;
  private Instant createdAt;
}
