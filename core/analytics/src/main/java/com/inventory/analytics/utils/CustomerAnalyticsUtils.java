package com.inventory.analytics.utils;

import com.inventory.analytics.rest.dto.response.CustomerAnalyticsDto;
import com.inventory.analytics.rest.dto.response.CustomerSummaryDto;
import com.inventory.analytics.utils.AnalyticsSalesQueries.CustomerTotal;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.repository.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class CustomerAnalyticsUtils {

  @Autowired
  private CustomerRepository customerRepository;

  /** The customers of these ids, read once per request. */
  public Map<String, Customer> customersById(List<CustomerTotal> totals) {
    Set<String> ids = new HashSet<>();
    for (CustomerTotal t : totals) {
      if (t.customerId() != null) {
        ids.add(t.customerId());
      }
    }
    Map<String, Customer> out = new HashMap<>();
    if (!ids.isEmpty()) {
      for (Customer c : customerRepository.findAllById(ids)) {
        out.put(c.getId(), c);
      }
    }
    return out;
  }

  /**
   * Per-customer analytics from MongoDB's all-time per-customer totals. Bills with a customer id
   * group by id (name, phone and email from the customer record; the earliest name typed on their
   * bills if the record is gone); bills with only a typed name group by that name.
   */
  public List<CustomerAnalyticsDto> calculateCustomerAnalytics(List<CustomerTotal> totals, Map<String, Customer> customers) {
    Map<String, CustomerPurchaseData> customerMap = new HashMap<>();
    for (CustomerTotal t : totals) {
      String customerKey = t.customerId() != null ? "ID:" + t.customerId() : "NAME:" + t.billName();
      CustomerPurchaseData data = new CustomerPurchaseData(customerKey, t.customerId(), t.billName());
      Customer customer = t.customerId() == null ? null : customers.get(t.customerId());
      if (customer != null) {
        data.customerName = customer.getName();
        data.customerPhone = customer.getPhone();
        data.customerEmail = customer.getEmail();
      }
      data.totalPurchases = Math.toIntExact(t.bills());
      data.totalRevenue = t.grandTotal();
      data.firstPurchaseDate = t.firstSoldAt();
      data.lastPurchaseDate = t.lastSoldAt();
      data.purchaseCountInPeriod = Math.toIntExact(t.billsInPeriod());
      customerMap.put(customerKey, data);
    }

    Instant now = Instant.now();

    // Build response
    return customerMap.values().stream()
        .map(data -> {
          BigDecimal avgOrderValue = BigDecimal.ZERO;
          if (data.totalPurchases > 0) {
            avgOrderValue = data.totalRevenue
                .divide(BigDecimal.valueOf(data.totalPurchases), 2, RoundingMode.HALF_UP);
          }

          // Calculate purchase frequency (purchases per month)
          BigDecimal purchaseFrequency = BigDecimal.ZERO;
          if (data.firstPurchaseDate != null && data.lastPurchaseDate != null && data.totalPurchases > 1) {
            long daysBetween = ChronoUnit.DAYS.between(data.firstPurchaseDate, data.lastPurchaseDate);
            if (daysBetween > 0) {
              BigDecimal monthsBetween = BigDecimal.valueOf(daysBetween).divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
              purchaseFrequency = BigDecimal.valueOf(data.totalPurchases)
                  .divide(monthsBetween.max(BigDecimal.ONE), 2, RoundingMode.HALF_UP);
            } else {
              purchaseFrequency = BigDecimal.valueOf(data.totalPurchases);
            }
          } else if (data.totalPurchases == 1) {
            purchaseFrequency = BigDecimal.ONE;
          }

          // Customer lifetime value is same as total revenue for now
          BigDecimal customerLifetimeValue = data.totalRevenue;

          Long daysSinceLastPurchase = null;
          if (data.lastPurchaseDate != null) {
            daysSinceLastPurchase = ChronoUnit.DAYS.between(data.lastPurchaseDate, now);
          }

          Boolean isRepeatCustomer = data.totalPurchases > 1;

          return new CustomerAnalyticsDto(
              data.customerId,
              data.customerName,
              data.customerPhone,
              data.customerEmail,
              data.totalPurchases,
              data.totalRevenue,
              avgOrderValue,
              customerLifetimeValue,
              purchaseFrequency,
              data.firstPurchaseDate,
              data.lastPurchaseDate,
              daysSinceLastPurchase,
              isRepeatCustomer,
              data.purchaseCountInPeriod
          );
        })
        .sorted((a, b) -> b.getTotalRevenue().compareTo(a.getTotalRevenue()))
        .collect(Collectors.toList());
  }

  /**
   * Calculate customer summary statistics.
   */
  public CustomerSummaryDto calculateCustomerSummary(List<CustomerAnalyticsDto> customerAnalytics) {
    if (customerAnalytics.isEmpty()) {
      return new CustomerSummaryDto(0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
          BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    int totalCustomers = customerAnalytics.size();
    int newCustomers = (int) customerAnalytics.stream()
        .filter(c -> c.getTotalPurchases() != null && c.getTotalPurchases() == 1)
        .count();
    int returningCustomers = totalCustomers - newCustomers;

    BigDecimal newCustomerPercent = BigDecimal.ZERO;
    BigDecimal returningCustomerPercent = BigDecimal.ZERO;
    if (totalCustomers > 0) {
      newCustomerPercent = BigDecimal.valueOf(newCustomers)
          .divide(BigDecimal.valueOf(totalCustomers), 4, RoundingMode.HALF_UP)
          .multiply(BigDecimal.valueOf(100));
      returningCustomerPercent = BigDecimal.valueOf(returningCustomers)
          .divide(BigDecimal.valueOf(totalCustomers), 4, RoundingMode.HALF_UP)
          .multiply(BigDecimal.valueOf(100));
    }

    BigDecimal avgPurchaseFrequency = customerAnalytics.stream()
        .map(CustomerAnalyticsDto::getPurchaseFrequency)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(totalCustomers), 2, RoundingMode.HALF_UP);

    BigDecimal avgSpendPerCustomer = customerAnalytics.stream()
        .map(CustomerAnalyticsDto::getTotalRevenue)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(totalCustomers), 2, RoundingMode.HALF_UP);

    BigDecimal avgLifetimeValue = customerAnalytics.stream()
        .map(CustomerAnalyticsDto::getCustomerLifetimeValue)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(totalCustomers), 2, RoundingMode.HALF_UP);

    return new CustomerSummaryDto(
        totalCustomers,
        newCustomers,
        returningCustomers,
        newCustomerPercent,
        returningCustomerPercent,
        avgPurchaseFrequency,
        avgSpendPerCustomer,
        avgLifetimeValue
    );
  }

  // Helper class for aggregating customer purchase data
  private static class CustomerPurchaseData {
    String customerKey;
    String customerId;
    String customerName;
    String customerPhone;
    String customerEmail;
    int totalPurchases = 0;
    BigDecimal totalRevenue = BigDecimal.ZERO;
    Instant firstPurchaseDate;
    Instant lastPurchaseDate;
    int purchaseCountInPeriod = 0;

    CustomerPurchaseData(String customerKey, String customerId, String customerName) {
      this.customerKey = customerKey;
      this.customerId = customerId;
      this.customerName = customerName;
    }
  }
}

