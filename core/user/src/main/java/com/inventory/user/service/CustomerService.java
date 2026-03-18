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
import com.inventory.user.utils.TextUtils;
import com.inventory.user.validation.CustomerValidator;
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

  @Autowired
  private CustomerValidator customerValidator;


  public CustomerDto createCustomerDto(String shopId, CreateCustomerRequest request) {
    return customerMapper.toDto(findOrCreateCustomer(shopId, request));
  }

  @Transactional(readOnly = true)
  public CustomerDto searchCustomer(String shopId, String phone, String email) {
    customerValidator.validateShopId(shopId);
    customerValidator.validateCustomerSearchParams(phone, email);

    String normalizedPhone = TextUtils.trimToNull(phone);
    String normalizedEmail = TextUtils.trimToNull(email);
    boolean searchByPhone = StringUtils.hasText(normalizedPhone);
    String searchValue = searchByPhone ? normalizedPhone : normalizedEmail;

    Customer customer = (searchByPhone
        ? searchCustomerByPhone(normalizedPhone, shopId)
        : searchCustomerByEmail(normalizedEmail, shopId))
        .orElseThrow(() -> new ResourceNotFoundException(
            "Customer",
            searchByPhone ? "phone" : "email",
            "No customer found with " + (searchByPhone ? "phone " : "email ") + searchValue + " for shop " + shopId));

    return customerMapper.toDto(customer);
  }

  public Customer findOrCreateCustomer(String shopId, CreateCustomerRequest request) {

    customerValidator.validateShopId(shopId);
    customerValidator.validateCreateRequest(request);

    String phone = TextUtils.trimToNull(request.getPhone());
    String email = TextUtils.trimToNull(request.getEmail());

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

  /**
   * Paginated list (API). Validates shop and pagination; normalizes page/limit.
   */
  @Transactional(readOnly = true)
  public CustomerListResponse listCustomers(String shopId, Integer page, Integer limit, String q) {
    customerValidator.validateShopId(shopId);
    customerValidator.validateListParams(page, limit);
    int pageNum = (page != null && page >= 0) ? page : 0;
    int pageSize = (limit != null && limit > 0 && limit <= 100) ? limit : 20;
    return getCustomers(shopId, pageNum, pageSize, q);
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
   * Update a customer (API). All validation in service.
   */
  public CustomerDto updateCustomer(String customerId, String shopId, UpdateCustomerRequest request) {
    customerValidator.validateShopId(shopId);
    customerValidator.validateCustomerId(customerId);
    customerValidator.validateUpdateRequest(request);

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
