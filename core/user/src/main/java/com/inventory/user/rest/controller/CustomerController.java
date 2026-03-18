package com.inventory.user.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import com.inventory.user.rest.dto.request.UpdateCustomerRequest;
import com.inventory.user.rest.dto.response.CustomerDto;
import com.inventory.user.rest.dto.response.CustomerListResponse;
import com.inventory.user.service.CustomerService;
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

  @GetMapping("/search")
  public ResponseEntity<ApiResponse<CustomerDto>> search(
      @RequestParam(required = false) String phone,
      @RequestParam(required = false) String email,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(
        ApiResponse.success(customerService.searchCustomer(shopId, phone, email)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<CustomerDto>> create(
      @RequestBody CreateCustomerRequest request,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(
        ApiResponse.success(customerService.createCustomerDto(shopId, request)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<CustomerListResponse>> list(
      @RequestParam(required = false, defaultValue = "0") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer limit,
      @RequestParam(required = false) String q,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(
        ApiResponse.success(customerService.listCustomers(shopId, page, limit, q)));
  }

  @PatchMapping("/{customerId}")
  public ResponseEntity<ApiResponse<CustomerDto>> update(
      @PathVariable String customerId,
      @RequestBody UpdateCustomerRequest request,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(
        ApiResponse.success(customerService.updateCustomer(customerId, shopId, request)));
  }
}
