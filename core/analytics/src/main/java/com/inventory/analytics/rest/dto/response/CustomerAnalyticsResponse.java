package com.inventory.analytics.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAnalyticsResponse {
  private CustomerSummaryDto summary;
  private List<CustomerAnalyticsDto> topCustomers;
  private List<CustomerAnalyticsDto> allCustomers;
  private Map<String, Object> meta;
}
