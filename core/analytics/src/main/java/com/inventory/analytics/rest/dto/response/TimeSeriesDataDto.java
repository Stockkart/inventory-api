package com.inventory.analytics.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesDataDto {
  private String period;
  private Instant startTime;
  private Instant endTime;
  private BigDecimal revenue;
  private Integer purchaseCount;
  private BigDecimal averageOrderValue;
}
