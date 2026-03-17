package com.inventory.user.service;

import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.ShopCustomer;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.ShopCustomerRepository;
import com.inventory.user.mapper.CustomerMapper;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import com.inventory.user.rest.dto.request.UpdateCustomerRequest;
import com.inventory.user.rest.dto.response.CustomerDto;
import com.inventory.user.rest.dto.response.CustomerListResponse;
import com.inventory.common.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
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
   * Find or create a customer and link it to a shop. Caller must validate request (e.g. via CustomerValidator).
   */
  public Customer findOrCreateCustomer(String shopId, CreateCustomerRequest request) {
    String name = customerMapper.trimOrNull(request.getName());
    String phone = customerMapper.trimOrNull(request.getPhone());
    String email = customerMapper.trimOrNull(request.getEmail());

    Customer customer = null;
    if (StringUtils.hasText(phone)) {
      customer = customerRepository.findByPhone(phone).orElse(null);
    }
    if (customer == null && StringUtils.hasText(email)) {
      customer = customerRepository.findByEmail(email).orElse(null);
    }

    if (customer == null) {
      customer = customerMapper.toCustomer(request);
      customer = customerRepository.save(customer);
      log.info("Created new customer with ID: {}", customer.getId());
    } else {
      updateExistingCustomerFromCreateRequest(request, customer);
    }

    linkCustomerToShopIfNeeded(shopId, customer.getId());
    return customer;
  }

  private void updateExistingCustomerFromCreateRequest(CreateCustomerRequest request, Customer customer) {
    customerMapper.applyCreateRequest(request, customer);
    customerRepository.save(customer);
    log.info("Updated customer with ID: {}", customer.getId());
  }

  private void linkCustomerToShopIfNeeded(String shopId, String customerId) {
    if (!shopCustomerRepository.existsByShopIdAndCustomerId(shopId, customerId)) {
      ShopCustomer shopCustomer = customerMapper.toShopCustomer(shopId, customerId);
      shopCustomerRepository.save(shopCustomer);
      log.info("Linked customer {} to shop {}", customerId, shopId);
    }
  }

  @Transactional(readOnly = true)
  public Optional<Customer> getCustomerById(String customerId) {
    if (!StringUtils.hasText(customerId)) {
      return Optional.empty();
    }
    return customerRepository.findById(customerId.trim());
  }

  @Transactional(readOnly = true)
  public Optional<Customer> searchCustomerByPhone(String phone, String shopId) {
    return customerRepository.findByPhone(phone.trim())
        .filter(c -> shopCustomerRepository.existsByShopIdAndCustomerId(shopId, c.getId()));
  }

  @Transactional(readOnly = true)
  public Optional<Customer> searchCustomerByEmail(String email, String shopId) {
    return customerRepository.findByEmail(email.trim())
        .filter(c -> shopCustomerRepository.existsByShopIdAndCustomerId(shopId, c.getId()));
  }

  @Transactional(readOnly = true)
  public CustomerListResponse getCustomers(String shopId, int page, int limit, String query) {
    Pageable pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));

    if (StringUtils.hasText(query)) {
      return getCustomersByQuery(shopId, page, limit, query.trim(), pageable);
    }

    Page<ShopCustomer> shopCustomerPage =
        shopCustomerRepository.findByShopIdOrderByCreatedAtDesc(shopId, pageable);
    List<String> customerIds = shopCustomerPage.getContent().stream()
        .map(ShopCustomer::getCustomerId)
        .toList();
    if (customerIds.isEmpty()) {
      return new CustomerListResponse(List.of(), page, limit, 0, 0);
    }
    List<Customer> customers = customerRepository.findAllById(customerIds);
    List<CustomerDto> dtos = customers.stream().map(customerMapper::toDto).toList();
    return new CustomerListResponse(
        dtos,
        shopCustomerPage.getNumber(),
        shopCustomerPage.getSize(),
        shopCustomerPage.getTotalElements(),
        shopCustomerPage.getTotalPages());
  }

  private CustomerListResponse getCustomersByQuery(
      String shopId, int page, int limit, String query, Pageable pageable) {
    List<String> shopCustomerIds = shopCustomerRepository.findByShopId(shopId).stream()
        .map(ShopCustomer::getCustomerId)
        .toList();
    if (shopCustomerIds.isEmpty()) {
      return new CustomerListResponse(List.of(), page, limit, 0, 0);
    }
    List<Customer> matching = customerRepository.searchByQuery(query);
    List<Customer> shopCustomers = matching.stream()
        .filter(c -> shopCustomerIds.contains(c.getId()))
        .sorted((a, b) -> (b.getUpdatedAt() != null ? b.getUpdatedAt() : b.getCreatedAt())
            .compareTo(a.getUpdatedAt() != null ? a.getUpdatedAt() : a.getCreatedAt()))
        .toList();
    if (shopCustomers.isEmpty()) {
      return new CustomerListResponse(List.of(), page, limit, 0, 0);
    }
    long total = shopCustomers.size();
    int totalPages = (int) Math.ceil((double) total / limit);
    int from = page * limit;
    int to = Math.min(from + limit, shopCustomers.size());
    List<Customer> paged = from < shopCustomers.size() ? shopCustomers.subList(from, to) : List.of();
    List<CustomerDto> dtos = paged.stream().map(customerMapper::toDto).toList();
    return new CustomerListResponse(dtos, page, limit, total, totalPages);
  }

  /**
   * Update a customer. Caller must validate customerId, shopId and request. Throws if customer not found or not linked to shop.
   */
  public CustomerDto updateCustomer(String customerId, String shopId, UpdateCustomerRequest request) {
    if (!shopCustomerRepository.existsByShopIdAndCustomerId(shopId, customerId)) {
      throw new ResourceNotFoundException("Customer", "id", "Customer not found or not linked to your shop");
    }
    Customer customer = customerRepository.findById(customerId)
        .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", customerId));

    customerMapper.applyUpdate(request, customer);
    customer = customerRepository.save(customer);
    log.info("Updated customer with ID: {}", customer.getId());
    return customerMapper.toDto(customer);
  }
}
