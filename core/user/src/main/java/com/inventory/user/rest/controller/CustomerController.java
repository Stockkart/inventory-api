package com.inventory.user.rest.controller;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import com.inventory.user.rest.dto.request.UpdateCustomerRequest;
import com.inventory.user.rest.dto.response.CustomerDto;
import com.inventory.user.rest.dto.response.CustomerListResponse;
import com.inventory.user.mapper.CustomerMapper;
import com.inventory.user.service.CustomerService;
import com.inventory.user.validation.CustomerValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers")
@Slf4j
public class CustomerController {

  @Autowired
  private CustomerService customerService;

  @Autowired
  private CustomerMapper customerMapper;

  @Autowired
  private CustomerValidator customerValidator;

  @GetMapping("/search")
  public ResponseEntity<ApiResponse<CustomerDto>> search(
      @RequestParam(required = false) String phone,
      @RequestParam(required = false) String email,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    customerValidator.validateShopId(shopId);
    customerValidator.validateCustomerSearchParams(phone, email);

    boolean searchByPhone = org.springframework.util.StringUtils.hasText(phone);
    String searchValue = searchByPhone ? phone.trim() : email.trim();
    log.info("Searching customer by {}: {} for shop: {}",
        searchByPhone ? "phone" : "email", searchValue, shopId);

    Customer customer = (searchByPhone
        ? customerService.searchCustomerByPhone(phone.trim(), shopId)
        : customerService.searchCustomerByEmail(email.trim(), shopId))
        .orElseThrow(() -> new ResourceNotFoundException("Customer",
            searchByPhone ? "phone" : "email",
            "No customer found with " + (searchByPhone ? "phone " : "email ") + searchValue + " for shop " + shopId));
    return ResponseEntity.ok(ApiResponse.success(customerMapper.toDto(customer)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<CustomerDto>> create(
      @RequestBody CreateCustomerRequest request,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    customerValidator.validateShopId(shopId);
    customerValidator.validateCreateRequest(request);

    Customer customer = customerService.findOrCreateCustomer(shopId, request);
    return ResponseEntity.ok(ApiResponse.success(customerMapper.toDto(customer)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<CustomerListResponse>> list(
      @RequestParam(required = false, defaultValue = "0") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer limit,
      @RequestParam(required = false) String q,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    customerValidator.validateShopId(shopId);
    customerValidator.validateListParams(page, limit);

    int pageNum = (page != null && page >= 0) ? page : 0;
    int pageSize = (limit != null && limit > 0 && limit <= 100) ? limit : 20;
    CustomerListResponse response = customerService.getCustomers(shopId, pageNum, pageSize, q);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @PatchMapping("/{customerId}")
  public ResponseEntity<ApiResponse<CustomerDto>> update(
      @PathVariable String customerId,
      @RequestBody UpdateCustomerRequest request,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    customerValidator.validateShopId(shopId);
    customerValidator.validateCustomerId(customerId);
    customerValidator.validateUpdateRequest(request);

    CustomerDto response = customerService.updateCustomer(customerId, shopId, request);
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}
