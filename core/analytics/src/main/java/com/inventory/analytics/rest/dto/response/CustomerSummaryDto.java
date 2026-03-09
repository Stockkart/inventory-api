package com.inventory.analytics.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSummaryDto {
  private Integer totalCustomers;
  private Integer newCustomers;
  private Integer returningCustomers;
  private BigDecimal newCustomerPercentage;
  private BigDecimal returningCustomerPercentage;
  private BigDecimal averagePurchaseFrequency;
  private BigDecimal averageSpendPerCustomer;
  private BigDecimal averageCustomerLifetimeValue;
}
