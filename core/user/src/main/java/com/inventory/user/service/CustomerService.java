package com.inventory.user.service;

import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.ShopCustomer;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.mapper.CustomerMapper;
import com.inventory.user.domain.repository.ShopCustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Optional;

@Service
@Slf4j
@Transactional
public class CustomerService {

  @Autowired
  private CustomerRepository customerRepository;

  @Autowired
  private ShopCustomerRepository shopCustomerRepository;

  @Autowired
  private CustomerMapper customerMapper;

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
   * @param customerGstin customer GSTIN (optional)
   * @param customerDlNo customer D.L No. (optional)
   * @param customerPan customer PAN (optional)
   * @param customerUserId optional link to StockKart user (enables credit sync)
   * @return the customer entity, or null if no customer info provided
   */
  public Customer findOrCreateCustomer(String shopId, String customerName, String customerPhone,
                                        String customerAddress, String customerEmail,
                                        String customerGstin, String customerDlNo, String customerPan,
                                        String customerUserId) {
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

      // If customer not found, create a new one via mapper
      if (customer == null) {
        customer = customerMapper.toCustomer(customerName, customerPhone, customerAddress, customerEmail,
            customerGstin, customerDlNo, customerPan, customerUserId);
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
        if (StringUtils.hasText(customerGstin) && !customerGstin.trim().equals(customer.getGstin())) {
          customer.setGstin(customerGstin.trim());
          updated = true;
        }
        if (StringUtils.hasText(customerDlNo) && !customerDlNo.trim().equals(customer.getDlNo())) {
          customer.setDlNo(customerDlNo.trim());
          updated = true;
        }
        if (StringUtils.hasText(customerPan) && !customerPan.trim().equals(customer.getPan())) {
          customer.setPan(customerPan.trim());
          updated = true;
        }
        if (StringUtils.hasText(customerUserId) && !customerUserId.trim().equals(customer.getUserId())) {
          customer.setUserId(customerUserId.trim());
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
        ShopCustomer shopCustomer = customerMapper.toShopCustomer(shopId, customer.getId());
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
  public Optional<Customer> getCustomerById(String customerId) {
    if (customerId == null || customerId.trim().isEmpty()) {
      return Optional.empty();
    }
    return customerRepository.findById(customerId.trim());
  }

  /**
   * Search customer by phone for a shop.
   *
   * @param phone the phone number
   * @param shopId the shop ID
   * @return the customer if found and linked to shop, empty otherwise
   */
  @Transactional(readOnly = true)
  public Optional<Customer> searchCustomerByPhone(String phone, String shopId) {
    if (!StringUtils.hasText(phone)) {
      return Optional.empty();
    }

    // Find customer by phone
    Optional<Customer> customerOpt = customerRepository.findByPhone(phone.trim());

    if (customerOpt.isEmpty()) {
      return Optional.empty();
    }

    Customer customer = customerOpt.get();

    // Verify customer is linked to the shop
    if (!shopCustomerRepository.existsByShopIdAndCustomerId(shopId, customer.getId())) {
      return Optional.empty();
    }

    return customerOpt;
  }

  /**
   * Search customer by email for a shop.
   *
   * @param email the email address
   * @param shopId the shop ID
   * @return the customer if found and linked to shop, empty otherwise
   */
  @Transactional(readOnly = true)
  public Optional<Customer> searchCustomerByEmail(String email, String shopId) {
    if (!StringUtils.hasText(email)) {
      return Optional.empty();
    }

    Optional<Customer> customerOpt = customerRepository.findByEmail(email.trim());

    if (customerOpt.isEmpty()) {
      return Optional.empty();
    }

    Customer customer = customerOpt.get();

    // Verify customer is linked to the shop
    if (!shopCustomerRepository.existsByShopIdAndCustomerId(shopId, customer.getId())) {
      return Optional.empty();
    }

    return customerOpt;
  }
}

