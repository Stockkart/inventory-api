package com.inventory.accounting.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Per-shop fiscal month state. Closing a period freezes that (year, month) so subsequent postings
 * must use an open period; reopening flips it back to {@link PeriodStatus#OPEN}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "fiscal_periods")
@CompoundIndex(
    name = "shop_period_unique",
    def = "{'shopId': 1, 'year': 1, 'month': 1}",
    unique = true)
public class FiscalPeriod {

  @Id private String id;

  private String shopId;
  private int year;
  private int month;

  private PeriodStatus status;

  private Instant closedAt;
  private String closedByUserId;
  private Instant reopenedAt;
  private String reopenedByUserId;
}
