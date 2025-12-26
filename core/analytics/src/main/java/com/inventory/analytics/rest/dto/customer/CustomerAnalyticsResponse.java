package com.inventory.analytics.rest.dto.customer;

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
  private List<CustomerAnalyticsDto> topCustomers; // Top customers by revenue
  private List<CustomerAnalyticsDto> allCustomers; // All customers (optional, can be limited)
  private Map<String, Object> meta;
}

