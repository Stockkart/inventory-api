package com.inventory.analytics.mis.rest.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One company's stock value movement across a period, all at cost.
 *
 * <p>{@code closing = opening + purchase - sale + adjustment} holds exactly, on every
 * row and on the totals. That identity is the point: the closing written at a period
 * end becomes the opening the next period reads.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MisBankSummaryRowDto {
  private String company;
  private BigDecimal opening;
  private BigDecimal purchase;
  private BigDecimal sale;
  /** Stock corrections applied in the period. Usually zero; broken out so the row still foots. */
  private BigDecimal adjustment;
  private BigDecimal closing;
}
