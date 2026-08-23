package com.inventory.user.service;

import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.ShopCustomer;
import com.inventory.user.domain.model.enums.CustomerPartyType;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.ShopCustomerRepository;
import com.inventory.user.mapper.CustomerMapper;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import com.inventory.user.rest.dto.request.UpdateCustomerRequest;
import com.inventory.user.rest.dto.response.CustomerDto;
import com.inventory.user.rest.dto.response.CustomerListResponse;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@Transactional
public class CustomerService {

  public static final String GENERAL_CUSTOMER_NAME = "General Customer";

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
        .filter(c -> !c.isGeneralCustomer())
        .orElseThrow(() -> new ResourceNotFoundException(
            "Customer",
            searchByPhone ? "phone" : "email",
            "No customer found with " + (searchByPhone ? "phone " : "email ") + searchValue + " for shop " + shopId));

    return customerMapper.toDto(customer);
  }

  /**
   * Create or reuse a unique customer (requires phone/email/GSTIN/PAN/DL).
   */
  public Customer findOrCreateCustomer(String shopId, CreateCustomerRequest request) {
    customerValidator.validateShopId(shopId);
    customerValidator.validateCreateRequest(request);

    Customer customer = findByUniqueKeys(request).orElse(null);

    if (customer != null && customer.isGeneralCustomer()) {
      customer = null;
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

  /**
   * Resolve customer for cart / quotation / estimate.
   * Prefer an explicit shop-linked {@code customerId}; otherwise find-or-create by unique keys;
   * otherwise attach the shop General Customer placeholder.
   */
  public String resolvePurchaseCustomerId(
      String shopId,
      String customerId,
      CreateCustomerRequest identity) {
    customerValidator.validateShopId(shopId);

    if (StringUtils.hasText(customerId)) {
      String id = customerId.trim();
      if (shopCustomerRepository.existsByShopIdAndCustomerId(shopId, id)) {
        Optional<Customer> existing = customerRepository.findById(id);
        if (existing.isPresent()) {
          return id;
        }
      }
    }

    boolean hasUnique =
        identity != null
            && CustomerValidator.hasUniqueIdentifier(
                identity.getPhone(),
                identity.getEmail(),
                identity.getGstin(),
                identity.getPan(),
                identity.getDlNo());

    if (hasUnique) {
      if (!StringUtils.hasText(identity.getName())) {
        identity.setName("Customer");
      }
      CustomerPartyType partyType =
          identity.getPartyType() != null ? identity.getPartyType() : CustomerPartyType.CONSUMER;
      if (partyType != CustomerPartyType.CONSUMER
          && !CustomerValidator.hasUniqueIdentifier(
              identity.getPhone(),
              identity.getEmail(),
              identity.getGstin(),
              identity.getPan(),
              identity.getDlNo())) {
        throw new ValidationException(
            "Retailer, distributor, and wholesaler customers require phone, email, GSTIN, PAN, or DL");
      }
      identity.setPartyType(partyType);
      return findOrCreateCustomer(shopId, identity).getId();
    }

    return getOrCreateGeneralCustomer(shopId).getId();
  }

  /** One General Customer placeholder per shop for name/address-only (and empty) buyers. */
  public Customer getOrCreateGeneralCustomer(String shopId) {
    customerValidator.validateShopId(shopId);

    List<String> shopCustomerIds = shopCustomerRepository.findByShopId(shopId).stream()
        .map(ShopCustomer::getCustomerId)
        .toList();
    if (!shopCustomerIds.isEmpty()) {
      for (Customer c : customerRepository.findAllById(shopCustomerIds)) {
        if (c.isGeneralCustomer()) {
          return c;
        }
      }
    }

    Customer general = new Customer();
    general.setName(GENERAL_CUSTOMER_NAME);
    general.setPartyType(CustomerPartyType.CONSUMER);
    general.setIsGeneral(true);
    Instant now = Instant.now();
    general.setCreatedAt(now);
    general.setUpdatedAt(now);
    general = customerRepository.save(general);
    linkCustomerToShopIfNeeded(shopId, general.getId());
    log.info("Created general customer {} for shop {}", general.getId(), shopId);
    return general;
  }

  private Optional<Customer> findByUniqueKeys(CreateCustomerRequest request) {
    String phone = TextUtils.trimToNull(request.getPhone());
    String email = TextUtils.trimToNull(request.getEmail());
    String gstin = TextUtils.trimToNull(request.getGstin());
    String pan = TextUtils.trimToNull(request.getPan());
    String dlNo = TextUtils.trimToNull(request.getDlNo());

    if (StringUtils.hasText(phone)) {
      Optional<Customer> byPhone = customerRepository.findByPhone(phone);
      if (byPhone.isPresent()) {
        return byPhone;
      }
    }
    if (StringUtils.hasText(email)) {
      Optional<Customer> byEmail = customerRepository.findByEmail(email);
      if (byEmail.isPresent()) {
        return byEmail;
      }
    }
    if (StringUtils.hasText(gstin)) {
      Optional<Customer> byGstin = customerRepository.findByGstin(gstin);
      if (byGstin.isPresent()) {
        return byGstin;
      }
    }
    if (StringUtils.hasText(pan)) {
      Optional<Customer> byPan = customerRepository.findByPan(pan);
      if (byPan.isPresent()) {
        return byPan;
      }
    }
    if (StringUtils.hasText(dlNo)) {
      return customerRepository.findByDlNo(dlNo);
    }
    return Optional.empty();
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
        .filter(c -> !c.isGeneralCustomer())
        .filter(c -> shopCustomerRepository.existsByShopIdAndCustomerId(shopId, c.getId()));
  }

  @Transactional(readOnly = true)
  public Optional<Customer> searchCustomerByEmail(String email, String shopId) {
    return customerRepository.findByEmail(email.trim())
        .filter(c -> !c.isGeneralCustomer())
        .filter(c -> shopCustomerRepository.existsByShopIdAndCustomerId(shopId, c.getId()));
  }

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
    List<Customer> customers = customerRepository.findAllById(customerIds).stream()
        .filter(c -> !c.isGeneralCustomer())
        .toList();
    // Page totals may include general — re-filter content only for list UX
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
        .filter(c -> !c.isGeneralCustomer())
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

  public CustomerDto updateCustomer(String customerId, String shopId, UpdateCustomerRequest request) {
    customerValidator.validateShopId(shopId);
    customerValidator.validateCustomerId(customerId);
    customerValidator.validateUpdateRequest(request);

    if (!shopCustomerRepository.existsByShopIdAndCustomerId(shopId, customerId)) {
      throw new ResourceNotFoundException("Customer", "id", "Customer not found or not linked to your shop");
    }
    Customer customer = customerRepository.findById(customerId)
        .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", customerId));

    if (customer.isGeneralCustomer()) {
      throw new ValidationException("The general customer placeholder cannot be edited");
    }

    customerMapper.applyUpdate(request, customer);

    boolean stillHasUnique =
        CustomerValidator.hasUniqueIdentifier(
            customer.getPhone(),
            customer.getEmail(),
            customer.getGstin(),
            customer.getPan(),
            customer.getDlNo());
    if (!stillHasUnique) {
      throw new ValidationException(
          "A unique customer must keep at least one of phone, email, GSTIN, PAN, or DL");
    }
    CustomerPartyType partyType = customer.resolvedPartyType();
    if (partyType != CustomerPartyType.CONSUMER && !stillHasUnique) {
      throw new ValidationException(
          "Retailer, distributor, and wholesaler customers require phone, email, GSTIN, PAN, or DL");
    }

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
