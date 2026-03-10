package com.inventory.analytics.mapper;

import com.inventory.analytics.rest.dto.response.CustomerAnalyticsDto;
import com.inventory.analytics.rest.dto.response.CustomerSummaryDto;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/** Holder for mapper input when building CustomerAnalyticsResponse. */
@Data
@Builder
public class CustomerAnalyticsResponseParams {

  private CustomerSummaryDto summary;
  private List<CustomerAnalyticsDto> topCustomers;
  private List<CustomerAnalyticsDto> allCustomers;
  private Instant startDate;
  private Instant endDate;
  private int totalPurchases;
  private int totalAllPurchases;
  private int topN;
  private boolean includeAll;
  private int totalCustomers;
}
