package com.inventory.analytics.rest.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One day of trading: what was sold and how it was tendered, plus the month's running total.
 *
 * <p>{@code totalSale} is net of sales returns raised that day, and equals {@code cashAmount +
 * onlineAmount + creditAmount} — the three legs are the split of that same figure, not separate
 * money movements.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesMisDailyRowDto {
  private LocalDate txnDate;
  private BigDecimal totalSale;
  private BigDecimal cashAmount;
  private BigDecimal onlineAmount;
  private BigDecimal creditAmount;
  /** Running total of {@code totalSale} within the calendar month, restarting on the 1st. */
  private BigDecimal monthToDateTotal;
}
