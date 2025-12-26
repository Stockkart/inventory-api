package com.inventory.analytics.rest.dto.customer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSummaryDto {
  private Integer totalCustomers;
  private Integer newCustomers; // Customers with only 1 purchase
  private Integer returningCustomers; // Customers with 2+ purchases
  private BigDecimal newCustomerPercentage;
  private BigDecimal returningCustomerPercentage;
  private BigDecimal averagePurchaseFrequency; // Average purchases per month per customer
  private BigDecimal averageSpendPerCustomer;
  private BigDecimal averageCustomerLifetimeValue;
}

