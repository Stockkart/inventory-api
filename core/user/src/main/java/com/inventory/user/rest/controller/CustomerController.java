package com.inventory.user.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.rest.dto.customer.CustomerDto;
import com.inventory.user.rest.mapper.CustomerMapper;
import com.inventory.user.service.CustomerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
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

  @GetMapping("/search")
  public ResponseEntity<ApiResponse<CustomerDto>> search(
      @RequestParam(required = false) String phone,
      HttpServletRequest httpRequest) {
    // Get shopId from request attributes (set by AuthenticationInterceptor)
    String shopId = (String) httpRequest.getAttribute("shopId");

    // Validate shopId
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated or shop not found");
    }

    // Validate phone parameter
    if (!StringUtils.hasText(phone)) {
      throw new ValidationException("Phone number is required for search");
    }

    log.info("Searching customer by phone: {} for shop: {}", phone, shopId);

    try {
      // Search customer by phone for the shop
      java.util.Optional<Customer> customerOpt = customerService.searchCustomerByPhone(phone, shopId);

      if (customerOpt.isEmpty()) {
        throw new ResourceNotFoundException("Customer", "phone",
            "No customer found with phone " + phone + " for shop " + shopId);
      }

      Customer customer = customerOpt.get();

      // Map to response
      CustomerDto response = customerMapper.toDto(customer);

      log.info("Found customer with ID: {} for shop: {}", customer.getId(), shopId);
      return ResponseEntity.ok(ApiResponse.success(response));

    } catch (ValidationException | ResourceNotFoundException e) {
      log.warn("Search customer failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while searching customer: {}", e.getMessage(), e);
      throw new BaseException(
          ErrorCode.INTERNAL_SERVER_ERROR,
          "Error searching customer");
    } catch (Exception e) {
      log.error("Unexpected error while searching customer: {}", e.getMessage(), e);
      throw new BaseException(
          ErrorCode.INTERNAL_SERVER_ERROR,
          "Failed to search customer");
    }
  }
}

