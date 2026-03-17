package com.inventory.user.mapper;

import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.ShopCustomer;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import com.inventory.user.rest.dto.request.UpdateCustomerRequest;
import com.inventory.user.rest.dto.response.CustomerDto;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CustomerMapper {

  @Mapping(target = "customerId", source = "id")
  @Mapping(target = "panNo", ignore = true)
  CustomerDto toDto(Customer customer);

  @AfterMapping
  default void setPanNoFromGstin(@MappingTarget CustomerDto dto, Customer customer) {
    String gstin = customer.getGstin();
    if (gstin != null && gstin.length() >= 12) {
      dto.setPanNo(gstin.substring(2, 12));
    }
  }

  default Customer toCustomer(CreateCustomerRequest request) {
    if (request == null) {
      return null;
    }
    Customer c = new Customer();
    c.setName(trimOrNull(request.getName()));
    c.setPhone(trimOrNull(request.getPhone()));
    c.setAddress(trimOrNull(request.getAddress()));
    c.setEmail(trimOrNull(request.getEmail()));
    c.setGstin(trimOrNull(request.getGstin()));
    c.setDlNo(trimOrNull(request.getDlNo()));
    c.setPan(trimOrNull(request.getPan()));
    Instant now = Instant.now();
    c.setCreatedAt(now);
    c.setUpdatedAt(now);
    return c;
  }

  default void applyUpdate(UpdateCustomerRequest request, @MappingTarget Customer customer) {
    if (request == null) {
      return;
    }
    if (request.getName() != null) {
      customer.setName(trimOrNull(request.getName()));
    }
    if (request.getPhone() != null) {
      customer.setPhone(trimOrNull(request.getPhone()));
    }
    if (request.getEmail() != null) {
      customer.setEmail(trimOrNull(request.getEmail()));
    }
    if (request.getAddress() != null) {
      customer.setAddress(trimOrNull(request.getAddress()));
    }
    if (request.getGstin() != null) {
      customer.setGstin(trimOrNull(request.getGstin()));
    }
    if (request.getDlNo() != null) {
      customer.setDlNo(trimOrNull(request.getDlNo()));
    }
    if (request.getPan() != null) {
      customer.setPan(trimOrNull(request.getPan()));
    }
    customer.setUpdatedAt(Instant.now());
  }

  /** Apply create request fields to an existing customer (e.g. when reusing by phone/email). */
  default void applyCreateRequest(CreateCustomerRequest request, @MappingTarget Customer customer) {
    if (request == null) {
      return;
    }
    customer.setName(trimOrNull(request.getName()));
    customer.setPhone(trimOrNull(request.getPhone()));
    customer.setEmail(trimOrNull(request.getEmail()));
    customer.setAddress(trimOrNull(request.getAddress()));
    customer.setGstin(trimOrNull(request.getGstin()));
    customer.setDlNo(trimOrNull(request.getDlNo()));
    customer.setPan(trimOrNull(request.getPan()));
    customer.setUpdatedAt(Instant.now());
  }

  default String trimOrNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  default ShopCustomer toShopCustomer(String shopId, String customerId) {
    ShopCustomer sc = new ShopCustomer();
    sc.setShopId(shopId);
    sc.setCustomerId(customerId);
    sc.setCreatedAt(Instant.now());
    return sc;
  }
}
