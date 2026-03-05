package com.inventory.plan.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Monthly usage per shop. One record per shop per month.
 * Data is retained for up to 12 months, then purged/reset.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "usage")
@CompoundIndex(name = "shop_month_idx", def = "{'shopId': 1, 'month': 1}", unique = true)
public class Usage {

  @Id
  private String id;
  private String shopId;
  /** Month in yyyy-MM format. */
  private String month;
  private BigDecimal billingAmountUsed;
  private Integer billCountUsed;
  private Integer smsUsed;
  private Integer whatsappUsed;
  private Instant createdAt;
  private Instant updatedAt;
}
