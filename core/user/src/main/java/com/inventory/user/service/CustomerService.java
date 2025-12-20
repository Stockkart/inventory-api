package com.inventory.user.service;

import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.ShopCustomer;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.ShopCustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional
public class CustomerService {

  @Autowired
  private CustomerRepository customerRepository;

  @Autowired
  private ShopCustomerRepository shopCustomerRepository;

  /**
   * Find or create a customer and link it to a shop.
   * If customer info is provided, it will find existing customer by phone/email,
   * or create a new one if not found. Then creates the shop-customer relationship.
   *
   * @param shopId the shop ID
   * @param customerName customer name
   * @param customerPhone customer phone (optional)
   * @param customerAddress customer address (optional)
   * @param customerEmail customer email (optional)
   * @return the customer entity, or null if no customer info provided
   */
  public Customer findOrCreateCustomer(String shopId, String customerName, String customerPhone,
                                        String customerAddress, String customerEmail) {
    // If no customer name provided, return null
    if (!StringUtils.hasText(customerName)) {
      return null;
    }

    try {
      // Try to find existing customer by phone (preferred) or email
      Customer customer = null;
      if (StringUtils.hasText(customerPhone)) {
        customer = customerRepository.findByPhone(customerPhone.trim())
            .orElse(null);
      }
      if (customer == null && StringUtils.hasText(customerEmail)) {
        customer = customerRepository.findByEmail(customerEmail.trim())
            .orElse(null);
      }

      // If customer not found, create a new one
      if (customer == null) {
        customer = new Customer();
        customer.setName(customerName.trim());
        customer.setPhone(StringUtils.hasText(customerPhone) ? customerPhone.trim() : null);
        customer.setAddress(StringUtils.hasText(customerAddress) ? customerAddress.trim() : null);
        customer.setEmail(StringUtils.hasText(customerEmail) ? customerEmail.trim() : null);
        customer.setCreatedAt(Instant.now());
        customer.setUpdatedAt(Instant.now());

        customer = customerRepository.save(customer);
        log.info("Created new customer with ID: {}", customer.getId());
      } else {
        // Update existing customer if new info is provided
        boolean updated = false;
        if (StringUtils.hasText(customerName) && !customerName.trim().equals(customer.getName())) {
          customer.setName(customerName.trim());
          updated = true;
        }
        if (StringUtils.hasText(customerPhone) && !customerPhone.trim().equals(customer.getPhone())) {
          customer.setPhone(customerPhone.trim());
          updated = true;
        }
        if (StringUtils.hasText(customerAddress) && !customerAddress.trim().equals(customer.getAddress())) {
          customer.setAddress(customerAddress.trim());
          updated = true;
        }
        if (StringUtils.hasText(customerEmail) && !customerEmail.trim().equals(customer.getEmail())) {
          customer.setEmail(customerEmail.trim());
          updated = true;
        }

        if (updated) {
          customer.setUpdatedAt(Instant.now());
          customer = customerRepository.save(customer);
          log.info("Updated customer with ID: {}", customer.getId());
        }
      }

      // Create shop-customer relationship if it doesn't exist
      if (!shopCustomerRepository.existsByShopIdAndCustomerId(shopId, customer.getId())) {
        ShopCustomer shopCustomer = new ShopCustomer();
        shopCustomer.setShopId(shopId);
        shopCustomer.setCustomerId(customer.getId());
        shopCustomer.setCreatedAt(Instant.now());
        shopCustomerRepository.save(shopCustomer);
        log.info("Linked customer {} to shop {}", customer.getId(), shopId);
      }

      return customer;
    } catch (Exception e) {
      log.error("Error finding or creating customer for shop: {}", shopId, e);
      // Don't fail cart upsert if customer creation fails
      return null;
    }
  }

  /**
   * Get a customer by ID.
   *
   * @param customerId the customer ID
   * @return an Optional containing the customer if found, empty otherwise
   */
  @Transactional(readOnly = true)
  public java.util.Optional<Customer> getCustomerById(String customerId) {
    if (customerId == null || customerId.trim().isEmpty()) {
      return java.util.Optional.empty();
    }
    return customerRepository.findById(customerId.trim());
  }

  /**
   * Search customers for a shop.
   *
   * @param shopId the shop ID
   * @param query the search query
   * @return list of matching customers for the shop
   */
  @Transactional(readOnly = true)
  public List<Customer> searchCustomers(String shopId, String query) {
    // Get all customer IDs for this shop
    List<String> customerIds = shopCustomerRepository.findByShopId(shopId).stream()
        .map(ShopCustomer::getCustomerId)
        .collect(Collectors.toList());

    if (customerIds.isEmpty()) {
      return List.of();
    }

    // If query provided, search customers and filter by shop's customers
    if (StringUtils.hasText(query)) {
      List<Customer> allMatchingCustomers = customerRepository.searchByQuery(query.trim());
      return allMatchingCustomers.stream()
          .filter(c -> customerIds.contains(c.getId()))
          .collect(Collectors.toList());
    }

    // If no query, return all customers for the shop
    return customerRepository.findAllById(customerIds);
  }

  /**
   * Search customer by phone for a shop.
   *
   * @param phone the phone number
   * @param shopId the shop ID
   * @return the customer if found and linked to shop, empty otherwise
   */
  @Transactional(readOnly = true)
  public java.util.Optional<Customer> searchCustomerByPhone(String phone, String shopId) {
    if (!StringUtils.hasText(phone)) {
      return java.util.Optional.empty();
    }

    // Find customer by phone
    java.util.Optional<Customer> customerOpt = customerRepository.findByPhone(phone.trim());

    if (customerOpt.isEmpty()) {
      return java.util.Optional.empty();
    }

    Customer customer = customerOpt.get();

    // Verify customer is linked to the shop
    if (!shopCustomerRepository.existsByShopIdAndCustomerId(shopId, customer.getId())) {
      return java.util.Optional.empty();
    }

    return customerOpt;
  }
}

