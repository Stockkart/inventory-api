package com.inventory.user.mapper;

import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.ShopCustomer;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import com.inventory.user.rest.dto.request.UpdateCustomerRequest;
import com.inventory.user.rest.dto.response.CustomerDto;
import com.inventory.user.utils.TextUtils;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

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
    c.setName(TextUtils.trimToNull(request.getName()));
    c.setPhone(TextUtils.trimToNull(request.getPhone()));
    c.setAddress(TextUtils.trimToNull(request.getAddress()));
    c.setEmail(TextUtils.trimToNull(request.getEmail()));
    c.setGstin(TextUtils.trimToNull(request.getGstin()));
    c.setDlNo(TextUtils.trimToNull(request.getDlNo()));
    c.setPan(TextUtils.trimToNull(request.getPan()));
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
      customer.setName(TextUtils.trimToNull(request.getName()));
    }
    if (request.getPhone() != null) {
      customer.setPhone(TextUtils.trimToNull(request.getPhone()));
    }
    if (request.getEmail() != null) {
      customer.setEmail(TextUtils.trimToNull(request.getEmail()));
    }
    if (request.getAddress() != null) {
      customer.setAddress(TextUtils.trimToNull(request.getAddress()));
    }
    if (request.getGstin() != null) {
      customer.setGstin(TextUtils.trimToNull(request.getGstin()));
    }
    if (request.getDlNo() != null) {
      customer.setDlNo(TextUtils.trimToNull(request.getDlNo()));
    }
    if (request.getPan() != null) {
      customer.setPan(TextUtils.trimToNull(request.getPan()));
    }
    customer.setUpdatedAt(Instant.now());
  }

  /** Apply create request fields to an existing customer (e.g. when reusing by phone/email). */
  default void applyCreateRequest(CreateCustomerRequest request, @MappingTarget Customer customer) {
    if (request == null) {
      return;
    }
    customer.setName(TextUtils.trimToNull(request.getName()));
    customer.setPhone(TextUtils.trimToNull(request.getPhone()));
    customer.setEmail(TextUtils.trimToNull(request.getEmail()));
    customer.setAddress(TextUtils.trimToNull(request.getAddress()));
    customer.setGstin(TextUtils.trimToNull(request.getGstin()));
    customer.setDlNo(TextUtils.trimToNull(request.getDlNo()));
    customer.setPan(TextUtils.trimToNull(request.getPan()));
    customer.setUpdatedAt(Instant.now());
  }

  default ShopCustomer toShopCustomer(String shopId, String customerId) {
    ShopCustomer sc = new ShopCustomer();
    sc.setShopId(shopId);
    sc.setCustomerId(customerId);
    sc.setCreatedAt(Instant.now());
    return sc;
  }
}
