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
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.utils.constants.UserMetricsConstants;
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
import java.util.regex.Pattern;

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

  @Autowired
  private MetricsWrapper metrics;


  public CustomerDto createCustomerDto(String shopId, CreateCustomerRequest request) {
    CustomerDto dto = customerMapper.toDto(findOrCreateCustomer(shopId, request));
    metrics.record(
        UserMetricsConstants.CUSTOMERS_TOTAL,
        1,
        "module",
        UserMetricsConstants.MODULE,
        "operation",
        "create");
    return dto;
  }

  @Transactional(readOnly = true)
  public CustomerDto searchCustomer(String shopId, String phone, String email, String name) {
    customerValidator.validateShopId(shopId);
    customerValidator.validateCustomerSearchParams(phone, email, name);

    String normalizedPhone = TextUtils.trimToNull(phone);
    String normalizedEmail = TextUtils.trimToNull(email);
    String normalizedName = TextUtils.trimToNull(name);

    String field;
    String searchValue;
    Optional<Customer> found;
    if (StringUtils.hasText(normalizedPhone)) {
      field = "phone";
      searchValue = normalizedPhone;
      found = searchCustomerByPhone(normalizedPhone, shopId);
    } else if (StringUtils.hasText(normalizedEmail)) {
      field = "email";
      searchValue = normalizedEmail;
      found = searchCustomerByEmail(normalizedEmail, shopId);
    } else {
      field = "name";
      searchValue = normalizedName;
      found = searchCustomerByName(normalizedName, shopId);
    }

    Customer customer = found.orElseThrow(() -> new ResourceNotFoundException(
        "Customer", field,
        "No customer found with " + field + " " + searchValue + " for shop " + shopId));

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
  /**
   * The shop's customer with this name, or empty when the name does not decide it.
   *
   * <p>Names are compared with case and spacing ignored. A party master pads the
   * town out with spaces -- {@code ABID MEDICAL HALL             BARACHATTI} --
   * so a name typed at the counter matches nothing when compared literally.
   *
   * <p>Several customers can share a name: three shops here trade as AGARWAL
   * MEDICAL HALL in three towns. Where that happens the name has not identified
   * anyone and this returns empty rather than picking one, since attributing a
   * sale or a history to the wrong shop is worse than finding nothing.
   */
  public Optional<Customer> searchCustomerByName(String name, String shopId) {
    String wanted = collapseSpaces(name);
    if (!StringUtils.hasText(wanted)) {
      return Optional.empty();
    }
    List<Customer> fits = customerRepository.searchByQuery(Pattern.quote(name.trim())).stream()
        .filter(c -> wanted.equalsIgnoreCase(collapseSpaces(c.getName())))
        .filter(c -> shopCustomerRepository.existsByShopIdAndCustomerId(shopId, c.getId()))
        .toList();
    return fits.size() == 1 ? Optional.of(fits.get(0)) : Optional.empty();
  }

  private static String collapseSpaces(String value) {
    return value == null ? "" : value.trim().replaceAll("\\s+", " ");
  }

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
    metrics.record(
        UserMetricsConstants.CUSTOMERS_TOTAL,
        1,
        "module",
        UserMetricsConstants.MODULE,
        "operation",
        "update");
    return customerMapper.toDto(customer);
  }
}
