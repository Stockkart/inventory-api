package com.inventory.analytics.helper;

import com.inventory.analytics.rest.dto.customer.CustomerAnalyticsDto;
import com.inventory.analytics.rest.dto.customer.CustomerSummaryDto;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseStatus;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.repository.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class CustomerAnalyticsHelper {

  @Autowired
  private PurchaseRepository purchaseRepository;

  @Autowired
  private CustomerRepository customerRepository;

  /**
   * Get all completed purchases for a shop.
   */
  public List<Purchase> getCompletedPurchases(String shopId) {
    return purchaseRepository.findByShopId(shopId, Pageable.unpaged())
        .getContent()
        .stream()
        .filter(p -> p.getStatus() == PurchaseStatus.COMPLETED)
        .filter(p -> p.getSoldAt() != null)
        .collect(Collectors.toList());
  }

  /**
   * Get completed purchases within a date range.
   */
  public List<Purchase> getCompletedPurchases(String shopId, Instant startDate, Instant endDate) {
    return purchaseRepository.findByShopId(shopId, Pageable.unpaged())
        .getContent()
        .stream()
        .filter(p -> p.getStatus() == PurchaseStatus.COMPLETED)
        .filter(p -> p.getSoldAt() != null)
        .filter(p -> !p.getSoldAt().isBefore(startDate) && !p.getSoldAt().isAfter(endDate))
        .collect(Collectors.toList());
  }

  /**
   * Calculate customer analytics from purchases.
   */
  public List<CustomerAnalyticsDto> calculateCustomerAnalytics(String shopId, List<Purchase> purchases, Instant startDate, Instant endDate) {
    Map<String, CustomerPurchaseData> customerMap = new HashMap<>();
    Set<String> customerIds = new HashSet<>();
    Set<String> customerNames = new HashSet<>();

    // Collect all customer identifiers
    for (Purchase purchase : purchases) {
      if (purchase.getCustomerId() != null) {
        customerIds.add(purchase.getCustomerId());
      } else if (purchase.getCustomerName() != null) {
        customerNames.add(purchase.getCustomerName());
      }
    }

    // Get customer details
    Map<String, Customer> customerMapById = new HashMap<>();
    for (String customerId : customerIds) {
      customerRepository.findById(customerId).ifPresent(customer -> customerMapById.put(customerId, customer));
    }

    // Process purchases
    for (Purchase purchase : purchases) {
      String customerKey;
      String customerId = purchase.getCustomerId();
      String customerName = purchase.getCustomerName();

      if (customerId != null) {
        customerKey = "ID:" + customerId;
      } else if (customerName != null) {
        customerKey = "NAME:" + customerName;
      } else {
        continue; // Skip purchases without customer info
      }

      CustomerPurchaseData data = customerMap.getOrDefault(customerKey, new CustomerPurchaseData(customerKey, customerId, customerName));

      BigDecimal purchaseTotal = purchase.getGrandTotal() != null ? purchase.getGrandTotal() : BigDecimal.ZERO;
      Instant soldAt = purchase.getSoldAt() != null ? purchase.getSoldAt() : purchase.getCreatedAt();

      data.totalPurchases++;
      data.totalRevenue = data.totalRevenue.add(purchaseTotal);
      data.purchaseDates.add(soldAt);

      // Track purchases in period
      if (startDate != null && endDate != null && soldAt != null) {
        if (!soldAt.isBefore(startDate) && !soldAt.isAfter(endDate)) {
          data.purchaseCountInPeriod++;
        }
      }

      // Update first and last purchase dates
      if (data.firstPurchaseDate == null || soldAt.isBefore(data.firstPurchaseDate)) {
        data.firstPurchaseDate = soldAt;
      }
      if (data.lastPurchaseDate == null || soldAt.isAfter(data.lastPurchaseDate)) {
        data.lastPurchaseDate = soldAt;
      }

      // Get customer details if available
      if (customerId != null && customerMapById.containsKey(customerId)) {
        Customer customer = customerMapById.get(customerId);
        data.customerName = customer.getName();
        data.customerPhone = customer.getPhone();
        data.customerEmail = customer.getEmail();
      } else if (customerName != null && data.customerName == null) {
        data.customerName = customerName;
      }

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
    List<Instant> purchaseDates = new ArrayList<>();
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

